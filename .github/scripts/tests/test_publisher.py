"""Hermetic tests for the shared module publication adapter .github/scripts/module-release.sh.

Every test runs the real shell: the actual repository publish.yml step calls the real adapter, and the adapter calls the real release_artifacts.py
helper. Every external command they can reach is a fake placed first on PATH in a temporary directory:

- ``gh`` keeps one repository's releases, release assets, tags and default-branch head in a local state
  directory and models only the calls the adapter is allowed to make. Anything else exits 96.
- ``npm`` models exactly one command, the pinned global install of @ultikits/cli 1.2.0.
- ``ultikits`` is the catalogue CLI. It refuses to run before that install and records each publish.
- ``mvn`` always fails: publication never builds.
- ``git`` forwards only read-only subcommands to the real git.
- ``python3`` runs the real interpreter in isolated mode with ``urllib.request.urlopen`` replaced by a
  fake internet that serves the npm registry document and the UltiCloud catalogue from the state
  directory. No test opens a network connection, installs a package or publishes anything.

The tests never import private release-control files; the dispatch inputs they use are sanitized fixtures.
Standard library only.
"""

import hashlib
import base64
import zlib
import re
import importlib.util
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile
from pathlib import Path

# Immutable upstream product fixtures, not another publisher implementation.
# Source: UltiKits/UltiTools-Reborn@f061477e9049f75ab297266d70604136998f01c6
# .github/scripts/module-release.sh and .github/scripts/release_artifacts.py.
# zlib + base85 keeps this allowlisted single-file suite hermetic in fresh public CI.
PINNED_SHA256 = {
    "module-release.sh": "f78dce83a34493c74acc2d6e2246d20687bc4b641d70ff82b9b094a022aad08f",
    "release_artifacts.py": "c0f3ed372aeafdcac58745c70ba789c64b8f111c6ebec53c5f40372899915516",
}
PINNED_DATA = {
    'module-release.sh': (
        'c-p-@YggOIlHdI+dIT9D7ui7W&fS5)jspgK!|Py^$=rm|5q8^XkR`7qLztNT?OTs-saui>*|}#q!(dC@Rn=A1_3G*@{m`1FN$Vzv'
        'S~9v9H-38CSQ3+XI18nl$WZ#JY^Jxu73r;?$e~DN<_D1+x_+9<OiX7tVKDHsAdZAT^rx9j#3)Y0RhR|ugDh2`cpSiah?Bcf7(b-V'
        '#u6av_=8(DWF-~yOOR$kG#1TqkloI1TKVXfn%{h!gks=_VJfoQAk9%C_6X)rftVo6v3SEEo%-3}7O;tKrm2vrxOvPZ!ti0ROr*G('
        '1z`qY26A2agMkF7LlHznIhF9gC<`9}!BC``KZX~GP7D)&l!>DtJD%ML%__zd$$KCQVGdK8#}5+e&Zc4P<3l||5FeQv?OApkCqedz'
        'cpg*n09*hXrI-NAiS0>KypzCRNssqWx<Dh04MQaXKy}0K{pb-;OyW$62LK7n!WqddLh;Vh48{QwqaaB$*UvIJnbxok7mCw{a^%l|'
        'KR1aV;nNWVllOsqAf1@TX^_Rq<2q8FAn$G-0g*MTScC}UaS*K&xi~~|l^II>NSVf4P)Cu)G^lsncDMfHCvoqGGZGz;rN{(K13yUu'
        'nKngROoDVm#Dh8kpeT;qKV=d#H=`Kz1~g2u4X~1U)KlbCCZne48_;3wbOwMx{Q*%T{h=5o@kAVT`^Q)Byv~QtS>L;8_m5%CTB;r}'
        'AsG3CENxCfb5b$%Gk=G5HXS6vRO<++ED(^2#D8EsB9mW~I$Wi|7O_gmNDK5nF*d`xRuTX_3PMTCsU>10VJ*1cjFgXs0$4_gKanVT'
        'F^DG<*gQ%qK>{2Y%M?})^9FZvxVBAX39=G(Ho`?k$hUs$?I{h}gBJnrp<wjrrdfiFek;EKT_80OLOh;H!2`0#J@J`P2$-a_&Q)4B'
        '@l)LDu4^dLThb)N@Q0tc(?pJfFXC-mt-CwKH@7mJ%ESfxh3fP6R({!|b*P=Il@Q6^598U;pbA6|tQvq>2*R>G09A_=*<g^A@&>tZ'
        '<ZuT-1Daxp-tlC0(0GNYOpnD3v5E={n*oJ{H073vMYD#pLW!Fsj3WsMP}x&pDD41EHRx4})&K^xxsgg^X|I8_^@jUr`x13h0!=Ir'
        'wu+jrR3`VVFd^t_D1Lggp*BH5LsJ|CqY+rpNY4hu5>yg|lfu$rlWRDdEWLc#bYKX;`r?y_RTv=Rj7fY?x@p@Td^3Qdg{=Tb1CKyF'
        'vxF!B^9EcV%|IW)5M(eboyJK96Tz~w7|2e-JR%DAUy{I=AO&RTK=9kT-kSF)x`C2c8c(D$9l)WR6ri!0AS0X_1|^QbSv&wbgvbJ*'
        '<w^;H;GNw!WeZZk_iqag-gDn7X6-fMDGMij+!5qZ{U{j0wq&B~XX#z(>cP!F(iqHIC%O-kIGO<Q+oTO@53~o!mK6DiyXxLg0{<qI'
        '>AE;N_WI}VJ7<7u6({@=-+)2BlVH7p?_evlbekQ_8ttU3lYaMox8LJgJSTVSg^H$f0D;7s3If1Rqe^BofVClRz!;P6q^S1OP-dX%'
        'n@X;he^We1aS{nTVoS`>Xzl>iH=r8yR6KzFRW1(a&ob=_!N8I2&+!<zn~es>H|{JJ(_jjE7=(=(n4mkc@sEEr8n{D9&=>9f_wA#O'
        '*SqNK?>fJLwgz`-{03q0%Vu+{x#3jDe&}5Gy60!RjvCOo>|C7hI*#ZbfG_DD{)V3qI)`oGruXi$eYSs$Pfpw2vp)QGF7X?vjdyX`'
        'IqZJKZ^xaJi_WD7P7;h9KG;8QpB;5h&X2sq?nwupH1>}>`|r=M`n%5ZSLM5YJ->Y41FAaP?()U22Tu-A(gUQsj^ef>0K-+UeS#o='
        'y*%%Cb~j+mNvGZGc<(xg=a&F_7TrbhLxf}b1t8f!KZQ5ITQ}!C(A4g?fugILsl}@WlN;T$9t=6@9N?QsI^z6YuXFjKbKsqJ`t1Xl'
        'k2Id0dne$LPjU>#qxNB+Sw~;Nn&nRbxYs?qxB|T`I0(;r=U12e9q+i)K2T3`(b2bqOMu|%Wq0+U)Am{Su+!@ozz{+E5=7hH@7pM}'
        'PUuD0Z`GpPeZX13zq~4aY4`h`(~G`BL=lO4+rR7{9d$0dXGb2OKBvW^)QJt}`}nufpgps?CcZY%@yfw%EF5~az1Yj2{|b88I%}VH'
        'YTSGGpz6L^j~(#uLjO3HF9-2=&umZ5`d#ti$DNPjlk2iC0E6OE<b!&C?TEb}w+MsQWLk*BD?>xG)p%;4(E(fe$ibuz`@}6~0uXbG'
        'DF&fKjF004Vo45w#43aiIwo4j4*~vFHWxkt#V`p%sbRVf7yxzvVIRf~9Na*Bx&{F<hqM3eou83UKu0KKI`F5!xn_e*mIpzHgdj1O'
        'Nf=`Q0ipF+X1ISr=)istrM~Sx@#;PaHxkY=4|2q{n9ng=n<bIhpaEZV&QC!~*+{H>{T!`S9)es_Sn$MmwuNKM-&J(AHl6haQxlnj'
        '&~fUk6bI2GuqA$2JX--WSv(GXA`9cxLz~7HEUjrCYg!rR#W_OMX0#Thl9vTFjc3U~dbePxZHUE=cleuO5o)1xuHdnv+LRlKnio{@'
        'SoX)!&#diV&!U+PPCZhVvx0_ghf{n}wxbp{#6giQOW^u=ERppd#3XT2fltOqfiD}M1tR8Th5dR-arjj{%fLg+van4r<QPws2dTxD'
        'hah$MikGz*%kImuo;`;(4-UPlKe$7CWTI8iJP0s-%o8ix!>m>A53yEUUoF-s_9$?#TWbCe!f08NV#$X(<|Fc$wb%$8I#m-Q1GN2Z'
        'b@r3CB~M~6w_vS$(?gLgg9t|2=IMB**Y8|-y{psH_T_JA7sWN@H&KqUFo7oirKKftYSgJXOV}2#Quyz4R5C7wNvH)j7l(lihhi>1'
        'kaL-Tj^?iG(ywVV?Kx~VVG`k_NxJT!3m<d!qTYJe8IeV}Ao3+(y6yuXS6^XG3_h)C6wld;@7d}$ZmV21H<qfiCv%lG7SP%W3vj6Y'
        'B=ml&<=``0$cAPY_!x$}XUXElhRB9oq(fDp-)U=Xz)|dS#twYOOwk5EH_~}?0Ac=Yfutbq1@_>XJq}wM_jJt$<uRiG<FQc?1?g=m'
        '+~JgPclp2QnH3lrfvSr0)Rw5^AZ20HWEzVY3&!P7Tp5JPI-HsegCu{nwbvTT`&KjyLvuWa*ZFwiCL{f<h(VoYUYi4J&k}zsc>SXD'
        'vD?Ssv@KN3WM;dZ%yLn4bBAix3sPL2-eNThYk&X;b90{hrrF@+F6VZh;w^7F24htUxGk19;diTutjX@enlK%7ge(6Wvh%W`)zP=Y'
        'U+4p^|BV*CIaH^d1)DZeH84i@C{9O$3G*gkh5fz7Oy-PVz|e<V%mY4&<t2EZi4CM`7!$k!?p>N06hv|=vnC1@Fm*?8HV!M^5$SC('
        'f|Nn*>==_u*-mACk;N~DVJAlw5ar~5F`y!WssJ(T1&aX`=~e}RSz=fWz{<@kFiM4q#bE4gt^%iA&sYTL^%^ULekN0Y&=|&%Y&1T7'
        '%H8<x|A^l|wcUUD?w=d(&tB8L{#&c`eOX)=>cw;OyrMM!y7<`i-0QC!>p#7Ds+n&~^;sw_0`v3F0OrRZYo`9ND_%F7ud6r;p+5tk'
        'fWP_AIvNX^e|wcgUIl_BaRmhC`0nWX>ra0#&1m$`&rf>ZK^Jv&bufhat6*aztWrZ<_paSLRyo(Fjq4|8%|UidABJmAgYm$$97L<`'
        ';KM<K>i)GKE&!y@)gBuSz&G<lkGBJ^hId3do8(Nfd<J;k!7LN*a0TLLceDvhLiSl;fPGbts>~j(rQl&6S0%js=*y`+0#ZeH>r`$U'
        'L=pT^=o+dxw7fdGLvI4qiE_DN`D_350=T{HwzA3e>8TW`H$T!3imD(J&zDk&%OqwVcmhULf}kZPkszbV9eY+p-{K=cAcm<TyFY!*'
        'ZsX{UaJvRKo#kH~viwWp63^w{cYfRb&_229h!8Ad${`UQM$)1g9EdK9u{QR)D0J^|)gA;IKZHSwbME9Lo%xB(zmrjus<__WeJMtP'
        'QCvjg7=;{A_prCSvb(}Zg>F)e$-R-`k>I7}lkmri;^#)mVXiWddAaa7FO?kUrDEg!b7MiFabAkB=N$CUjc|WXf&RQ4;?FgDnBVUJ'
        '1~eV*Ua!-~`NfxB_g|fYzI;IG35~kAJU_SsqWhPf4ncVLTfftr1Em1Ih|(D0#mUuCmuStSQCI~)oaeD9e_yM_v?^3~?}Y;pSOzPv'
        'QVhIFP>$(Q$b;Yq^djOdMG+X7C~eZm#I8!$?qve8fr>5oR6KV(m7-|XuyX)z6-I5NzNdEy<7>+3-rQ-L98Zzsm<Q#s1j#mlsdThf'
        '*T7UR&n|h{*8J}q+C5dv+jWMY)~3goG`4QM9=bqvzlkC*-RW`HfBW3D{}xEfTT0E^M8}6iUz6iv@jSzBVr)Q%#ky-iO#BcF$r#&m'
        'Xl<W0)Fkda%u1GPvf*`#@{3G8Pv|SPJ=&XCtlu>~ylXHC4;r0DE|h1-)*mmDqdkwT8j6@w+e^L?Fb&b?z_B{><wHlxAMy^Am9#k4'
        '9XyJ063?c1WDBW8@TbIsPnZSF9VBt8A*9?;BB`*?X%Qwsm@y972l!%?NJz6(Uksl5fdjyk(FR!aJ1>djYIVKh?(m2&i{mhLOBE`F'
        'k!n}wPC>15sEd6e4lx}eBv;xO=CfNs64;Mc>vOB+Jy}x2F;-Mz<Y9ci^Kvi^8K&&V6s5(<x`&G9_}C`{0yPC3j|3SvVSwZFaA18r'
        '27UueAk$N)-l~<vjUn<Wbrn&^5BBjq>H}}P2LiwiX9Ead5-FPvtkq+o6l0Y!<cjV-R!Gzlr-Sj03&Mp(xURCuD`>%f|H`ctPb((V'
        'tXPFe1p2&Lgw_$`1Co?}j$s-l`gzsyxXr~z1oFu!b{w}ke$%wtp<9jzxQhIx;|I3a`jr*2fdrU#juY!v4Km@gFgL5Q-S&ckFa=Ij'
        '&6;r_;;A!QrYx5RkI+3siq1f9>3qjKjT$m1G8s$v#wY0nsSDx<BN8VyG@HybW9J0~BP-*`Q<~*zFIz{sBDuE1&1oojsB16Rurm3-'
        'f5rJvCC;<zejnr&16)aS8>$9wP>d1<xAv+0G}lEvsLVyR#ppfc6xi6KBM5u1;s8@QYd0x7gd6I#QAr2cU95e{ouV5FcNrOR;g2EW'
        '5y1ppWdLKq0;r5jVxK74^kySwh#vQdQgR@cs7RI0MtEwB{ig(6r5U&*Qd;TwR`}C^j}_Av*)5$j!q?g$AP=|HxaCcDb~V~)^ODJf'
        '-Q(Z;LC99bjKyT$rw5y87IGuX4ReEQU@E{Yc^Tp!g_rytsaZrL#GBYBm5&XEi(;M6MkR)I#$|I<*^#7^<iPD1iz`Vgao6ESE&nKD'
        'tz(%^T6u|!xK*civ`lBAj6oJHN{oYO49pm%t;`>%t$S+$Ii>jSuZbA|yi}+oES!-zN2w)Ciwb&ACD~fiU6(Rfm;2HTI?Xj{&KI=9'
        'L(9n_OY<&0e_Uh>;Kkz*H*t&|dbt~Vj@^Kwg&G9U$|+7x6u$rK`^mOUf0gYPO(xr4xR`~?{X+W7Ja<$;W~FHcE_L$2`}FyM1Ct4v'
        '`^F;LD92$jb$#L~ZXTfPw(eSL3f-c&{q_-3vYV4sH^;UH+>+C`_dB<Es)6ewZ;e&`x;E7u_VS>ISU0$GA*t*#gyU3t*eGK%>lqY@'
        'BVW~WsO0MK1~dGt^|oRw%~KI$&taVXK+Q&IDulV0^X6MLq<D9LBW}IpAdpsMl}{Z3ecng>@1*Zm8vV&i7%S;Q^H9^ZOjOfF1CQn5'
        'Vd;@#A21qNgjxCBV*Tz)NlQpaAksm{KJ^2v;PGP2iqp2?bnF5{F~Ul3oxB{UY?ym2oHo(E4d16a#c9QZOlEl4BmIf9Ab8-FL579&'
        'y?V9%`pKy?!`85+*V|8}itw}6!nf4gwsb>?(7Dyy;nNP=;Bgq~s=F1g`ruY;u8Z6_KTaX}E^{6WcV^j&D+0#QnnItWy9kTISh<DJ'
        'JZ;gy|F3~bK@|57*4Ljj7F{y8Mpvp^C6Sh0D@#f#JZ~e*CQ)6e62?#meWfU8U}z{yZx%|~<d?$#1u=bbK)~0ER4ch{Y)UbFZawpU'
        'g=)-g`q+F}9;!Yk+eK0{1Jdu5NKU|W?zrgnGNR3*BZEh&TV}dtu+EW{k%23#@CzMIwf7kpQL1S>jY|JAjw;o+jZvzBxpq*w%NYli'
        'D&vkiURP#pcY{w|bHaBw8Vh_42i)vuED)PCquRbwcAh0fJk5lZcNU<~DxKxZO}d58D*)tBEf6&w<{AiJP|;OaHoe=TwCtTiJK$Yq'
        'fer1L7m#3hTHUl}-<2pzNEHpG3m2Zc>LZ-e9SfBYfsJ4VT@r^><>V_pc@tDkR!vq3K!S5IOU+FJQ`26*ysEai>acQi;C%jQmC+J8'
        '4q(4O+CEG_E$CVhu+!;7wMlE?aq@R!WrC4+MsZ$;(sgLRaT0M|Yh)^&o%;Hc!-eQ&^Y(~I99LfQOI!J&LK^wg^fp$Kj;53R9NKlq'
        'VSMA`hhQ9mbX~q@h~7SmU4J&jLKA)*%_4N4CTcXEMHI5N_v2>0%?txi;q&l9j8qAPIzz^rQ2IuV8pd=QS2t|5ImcJ^y&1zFRLYgh'
        'rTa6b?E=K9sU<g8G#9G7T1a387`F)l5Y+KqlO%n2nOj{J*NtKBD1_wPu9zZD4T0w4wY)+(4Q<!RVX?d7HC~R$gEw+NulcO1G7PIa'
        'uZzQ}s?2JrvU!q6^22yA!`orxv?<l9P@#(X`*e6>)%6xu?^KuCwpsEsE<dMSH^$z!sT@#=7G3hXx<E`x9~U<8zF+Ol$n{EENI~K('
        'QX3AB@2RL>Q#AxJ$Z%aOZ*3O@X!?i{;%YgCbak^F*WzE8$+#*?PzqhiiWV6X=5S#?nClh~^*b==bZ1dM^38T_JFH59o$3-TTlRK('
        '4kK+L5ytQ)!Op-Oa-MBk-d>%Gm@liDXzjAUl`MlkFG!T1{taC}3wnV_ms8u{mtz(yuQnA=Z3$hG<st`-<J)zcRzmtwT(tZ9$KO@3'
        'b`Kfo?#Ktu6;W{>DSfNn_O%*q(Y;v6mrHe81=8{bT(z;BdJ&^xuTJe|;yVLocWG5ge<+tY%6j$95(jsi)~+poUs@stBsbv2Yc@b;'
        '6h`W;l4G~ImJl1EYteM8gHMggQuhL19vPG?;Aos*9EjGdrjfQRuP%<t!50z91yLXmUPL)zSaU3?8wgy5ldnma6`-6QS!Sgq*Svky'
        '^OJG9yIM5;6xT%T9?~j|IygpSD)Iwp2eVRzwdNEEmB7oJ8h=&=wKx`MeZa*^oKdP3nMD{rz$q0*jAmfVRafdDk6<Vv@35En>6h!L'
        'n)q+61e^>*O%7Eu4MozzBDHKoA$WByo?qr>0e@;x=zqjO?GxRkuwZmKsWT)}DNy9Q`~Ry(K>;{jzfhWAcfG<mGGp>;su<rV3)_kn'
        'V#wpZ;*7Rbi_)U0l<y77xY^_-)dn2+qS{4a5Y41pOT_9msrgqbb}Bbu@<5O+q`q>X@BKsSkM|8W8vhHk{(Xo'
    ),
    'release_artifacts.py': (
        'c-q~4Yj@j5lIVB+3Iti-5D7_1jwd^zqs%I{;wU3ax{{pTjI70lNJv6V0vv#}qh$TxZ@v1721(hzyXRb=#3q1#R9AObS5?>J^Ut=I'
        'O}%|xl-qgvHk#ir?yB<ZtvHUKWo1<s<7^t$`64UId@{)DMRAjj7tySm<kRS;s-wkS9=)0_ii@h6HiP}sLnuAXvnG%3vT{=0+zhw2'
        '<jt_Ti>{Z&bkanbE;WWy#UxwgQB{ufepHmvU@))qc~<AtO+SJX(VIM<H`H1FI|6WhzsQ@YSZrZ~i(zzFPV#vUf0c_U8;|q(B7^3l'
        '-mCqKKJ@jr$lpOj(cN;EmC+<07fn%>2{u?pzbz-XIW}GaJk&7txdm`6uct+GH^}Dmx_X;WB6*QBC`~q#3emd)sxKEjzM{O1vRgpe'
        'FuJ%an&>W{&ht7dq!xUh=3Db=1^_PZvIW3%1L%cD@F8|vj-y$gmCa~tE0TGL;Lo_4-&12Z)v}J}+5NQ20Ik&xU=eT%s4W?ks5h^R'
        'SytaiKkuLQqnl~ASmZF3(do&{e$+tiF?5XIwj%4#Z`s>yQjPILLioqNUKER;me<i~Hhz;K!k`HNy)F<jH+3~*fb1S%HWwA3{rKSD'
        'o#tU6Fi|iA(WI#J@uC9wu$OmrvB;?zLiAX#5}38(CT{@0c~eZN$P8M1n@xG`K*7QIE+4;v&Q#exHKf+Anpnav4I7X+n-vRua}Uc9'
        '5Ke;tI9y!3!!>dXqb#|0wuGq$KpA9MV9>`nFXoGeXOov-KH(=`t_mh-xLE-5s9~6ya^V7bQ%rGfRnc;eGfLY*kG-k^+psim7O<FL'
        'z%qJVZGsON5$zlJHO-o)xB(cOCI`r}X`N@2dsrUU-s?@OcO~`%J<Ck#`AB#LHw2)A4+YOCDsa0Yh3LZ)1kwPc0w_Xu-QX_lK|`>m'
        'i)nT}%@h1EE1Frh7~e&`GJi`$pgk~2wzht(0srr+`i)F{MvJ4T_TOBV-0*%jRR}P{z`f_9Q3eegPHospB0FeaH3g1u0Q0~LC*##^'
        '?Gu_<T)r8NzNi7Qfc&Dw8U<qTWd_T^YAx#Vn`bcc8u3xp4$jmce+v=l8LVoUHK3){8)##Z;oq_rBOuB}U6nIL>D#O>7@G*7o?@B&'
        '_8)5oty$yw>D#kcF*<LS4IocXQ&sC}HRH!s#)BLb<$MWPj;wu%Jz0y&UDtg<rn~)!x@5PS$`D1HKt<Tkli;wL;uauT*3x2WlMSFo'
        '2uGdY77YMdM=<*fr2Og&5c1z(g%9`yBRUFH(_iE24=mH-rhowm%=2nC{GHYyP5|sS7?&(PT8o3KoZido7*{o(RzT_StO+*X8{WbK'
        'SYB^8;~ELb_6%z#vKe9W=P<Q7;94CausEMaWwp4&O%FqvEP=SdGN)6}ni|-9L=A~B-eqv`;*Pr~Y{+!b5Ri)C5bxz=Qizp3W^Kl;'
        'tqW@v>8&*eDzJ!3*u9Mquj~8h8Zd#c7)x1Q7x2EUqG^TP#5`Nv_3@EU)oi-804z~icqz1W80~8^3p@3`!F@+CB4@x;%tW^j1iFCL'
        'igb_Z3e(Pf0-VQ7z|b^ziOP)L4GdG#2N<VQIwGPRmR-apzGz_f0AGY^CL>6Hn2=10W{#UgZ34lfMNg2L-At=@sVa(70GpU`-$bxq'
        'GDZ;16pszpjkGBHA-HbTF}f^E<SS?+1EPA=Lm&BxY&l&Fu4`a|?r>wnf`wCgHeW=$eT!_t{;7-GTYw!omhre+mWu>>6nZ<D!8Rl~'
        'C&ew!I&y2m6UrOdJAA_Z8+2H_DPRqcr$w|od_3GyyYN0z?LgBm_7xSz6HB8(Aq-|@Qo}+t=T!kG;v%bWb3EL$+dlUVJ8to=;)7Mu'
        '{={^}Q!|h=fw?C>CM`<E9Z4B|py;XN(Kb)b2Egh0$Kk0)zCE;?L35Wm>`qfn-@>dDp#!pNCPuB7x?0}eMetuXFK{0NvZmGT(nb*d'
        'sepLbZ*$FdKbuy|i75^z$qka7th{Hc=(L4>U1vlV7!Bo3Q8U|STA=osQ`MXd^SE#tPGN;jqi??6QHO9TyKQUh960VtR!@M&!0>8h'
        'HKzB-0OyK$$*I3A8=}WdyogKfQ(^vuKr$Hx+DL~4w9DIJ1}7sDlC3gd5VJr-e9Gq=?{r;W@56FFGYe52GQwV(QKR5eA$WI-#a#IR'
        '7PEkDHvZX<w9V8U`llo9aE~g(IHTM7EeJZiHn}^mqMR-wGt7WOS*MyDM|z>+G0!yY=jVS1!iD_%co-f0PAHt@%_!Q5syF?uU3JX&'
        'qsP&*K~^-c>#BaLHwAobqOY0j<&$kz!PxIAlG{qdHbl)57UM#gUOek@r)T%n(iAxSr(5pI(sKh{L{CV1gdG7}p_x}T9@0xZ%@>ML'
        '!)IEHHAATAJe)kdkU*#dP(+5>YAPTybSy#vW^sln#cpjCGi)Bthg%q5ldESu6=A8W$D&fderw>gliyXNe$~2SbGKX+Q~d-hL;qf0'
        '!?Ff}O4n-c_0MHJh3<wl7Vn({m%OX<e=bpAkhgynb38cIBk&+Yz;~Vx52iWth8OS~PnDb>Q~b6?JR}~s&|oArPvH?Z>$9H@Uhb#A'
        '9GsmWo*YMe(eBny2mkS82Y%K0P?lb=j{oy=XYg${xVifC$<F(Ki4!dK&6Dm@-#npGKc1Yu+&{9KcZ>IS{_Apg@a+{=yZSPDJzVz!'
        '+dMlsJxR|F+I{a2{*ev-fP(38aP`%;_l#Aaot&IKUsLzWt@kC?dvSR2)2koSS7%3lO=jC(<D1LT_Epd8G}-pw{4MGIZS)%1z2tA('
        'f1~l7937lPyFF=43poQ|GUI*}iwurG<&B6a_){czXy<bxvBWhq1LjB~%qG1>R+(5E5jYfoZCF00)FBbSXe_}B=+x)xJf3L!GXpZd'
        'Fgp_)g|D=MQNpbhG{dcD2ges@ur$o*Hy)-Bn^6f_(iu$1P;nw7{IMXt6r8Har^22GN%$SIlSl)H(Th5pOmm@$cr4+7-=QK1I#k-O'
        'Ltqbzo146DSSl-sI;$>Gi^4QepE9@kq6sKN2wQZrv8Y0Vh}#eFi+Md56LNbqz&YW))*7pRbb5!J(Bs`?sFvsH{<FXDzc@H2oB~RN'
        '>uKxd{_)|D2j>^*-w%Fe#H()8VuGkvj}1Ps>Ir_}l0sG<f8PR=GMCbP3f$bMFkm3Malg%{#`Ed&wkT7;eG01r!I#;s>Xaz^#C=Z;'
        '0Z}e|#sij?0IbtlW=aM+0&eT^U%oNxCB1`#8Xr}qWNZKI;_%1)XV&n8L#(SQ{VOdZ)!BcZo*W<jngX_|9-K}Ctb)aegSusC-wue3'
        ';{$McP!(qOjAaz$v|#xIZsG8NGuCVzOJHSwSRZP{DdaV8OL@Sv7$tg=3qFr-mcSE;=#3}%!qPP=eVDAnBFEF4R_S#C97(3cC)}K<'
        'GE{u+aBKh7#ZM<^hZn!5d`RGmn&YB-ygSgUukGD<>&5;BEV_1~TNqD+1x{c6aCCV7QwnH@Qvk7k87n471S&=y4P7DbO4oZ1%k}Vh'
        '-&AG_fCY)h2~mL>X9xfL)#2H}^ORuXX)1VvCfWF2e&xRpSQCuT!1`NH3*_$c$1+>U!U4IT84;Mu|NZLZVn03q`{C*7{tvXtdR<K?'
        'TR)t@KHNV}_fJpHPJRJeXLg*Zk!ib$@JqiLt$*;BqDk$7x4<9)4~`3Ji&Q?*X<c2zG2is6Yos6`kkT%Q<7XAHfVj$7*e9w+M?eNF'
        '4U#FGX=FZeZ`Gs~oQn2iHY8pN8v^MuO4Fh&7HQgR^68CqK&lsb90JchYj8rYOoePRp|X8qH~VzJ6w_eMA&f3BC%qn*1On2N(n(@N'
        'a1E5Z4FMcQ%esv4W6!iH71I>N(?9aMN>hMOHDMafXT`#A5ZO>0W>hqohDQ#$0d{WKEGEFi4N3Iq#llUGqDKw>|GJE$M^Vqg0Z!cK'
        '!0UgaKd4<P#?96i4v6VgO0=wp61EIXup~V0kc)AF;a&bai?cn13|rAseX7WSw5T54R(r@H4=2mn+>}5~H696jkMTUL7wMb)zS+B|'
        'mw<=7Y=FT?vu0cr^eTzIief-jG6YfwsDhn=-(m$1>;O3Vdayg7tkX3df+*<#f#k2)?IID~(IW5}SAc3(PJ?s-B5Urn&ic;1$mLsy'
        '^jZo=#Z91jSiA}F+;Acv!``wX<xX%Is6$O?nc*+i=tH|hI!>EjLQOLSrX_|L1<n-Misdw`d#pE`%jWM#)9gB*n$3TP0&nK3T(L9U'
        '^K&2@Pk}A_gH>{(y8&J)Hy1bN%85t;T~I)efE5%OsP}~40*7OEMN!^VP^4;xQ<Q{yxSOdcm(71i4MKEset=?+sisCTM7lvIa5^iR'
        'hB%2R=Dmdcq<V>)ipJX~poVx}OBWW-^&9{~5t`w7dU$?x{P!M02^%*>r7vAY;9}7w&JJF<C04=-Rn?SOu+ElrIYkpd-w6MG>L`n*'
        'Mfv8TV_<F(D5b$E9+uZ}f^r^Su1@n7S>)G4TsL}m*EyaN=>!%eo@hC3O=z&F8yRVngTSyC{rP@N5kx4`k7$kK5~8x!oZsMgrjvQ;'
        'Qph?hU<v<%HEGIwvF?{DJ*p88@GG%ShP=FlH&*}xb!l%S{3zo<HB*YDC&inkda`mu)Y&_d+1BnKwO+6SEI35}C^RR1XLaGULX9DE'
        'zw=(_f?+^YRJ`8vV!=N65zt^k(CQ6ZZ|^qJ#qN=YVn{c>Jp=-T10tf!#m(TaF{~2i3sRc4^scJj>;>0=vqgKa-~@nXo>OB7tZ^~('
        'NQ(}Nt{G`j5<g1QWOj$W^r4Mp&5#AZ7PZB3!+BZ&v!iw|zIp)K&HjX=Za`n6pU+Q@1y`HShP-5W@Z~2x+Ua-)=}q?SvP|KjM|^y>'
        'gd_ILtiDxyR*-~m==u)+z1+EC#FddFwpi<#cIVj=M7NpRpQvT!b;-2K=wC%&hg>3vr4tY>>YS#MIved?^<^gOya7=g7N${v4FUSz'
        'B{q%|FnAU-qo>A_TmcJ!mr>MKf*(Pe-m~gIKltVN)zMLZ)%^$7g2wD>r-!Ep)Ee+;{`<t9LWX(BPt32$D{fSX4h$g0>#2@e;EHJY'
        'vDZ0YcCYZ-+pa9>;ypO9Lqbk)a<NRbw-@cSm;#U#P^?BC-r$qLS=LhkBmlD|sSXCW`GUJe#qN-s8$#7MahM^a{TeoTRs$!5&BI!Y'
        'V`ogTy|#IeyL?TUt@$L|g#>C;EwPL%c!9Gr{R8mFyEA+)IOG%uRlEf>EA{H|AI1Df)bJJ6@CRgt4o{eAc1Wcv{6jX8@@t4AR7jU4'
        'ZY;dLe=uFt2M=Gf?kTv5gP={MPq8Vm3sfqJKHH<GCSS;#WEFVkWI&->QB7E5c}ABQ5xph|*3n80?lmx^23QoaadtGU_*tlqd+N5K'
        '8`tAhnI$OnF+c$14G38cDdnD>J>wN&BNn<@i&%Lpp!ONjvfW6llZr0uem3pl)-uaWuu6<e<1$ge^%47o6^0GqcY$vjn_UnpP9>!V'
        'QgNshN3R#34`P?Q!ew8SOLHl#_G+Dru!7okQ9aul?ZMTT$>??SWpDW9-xB!uUjU2PS>i)Nt*q(PVh;DbJ#ROMt50O7@Asm|Bze%Y'
        '8sQhO<JfvT*u6r6r;4~hsP6dd?g$D8_)(q(x2C(A%sjfx5a7dMO1Y&@wv<0BakXsb#BZ_QTIi2Ro2`{Xq8iK5sLW$C9$w8*mcT<Z'
        '1L-0W{R~R_cu&m*&<;dPY3`~;VXTV~#+Hp!toNa!f~&b-S=UfLHbBOT-=MC$cCdwWOUUzWu245$Ru%?Cp{W@YGxRuF38NI;Yj0ge'
        'i%34Q$C0c!m8}_6D3F?PkoJs@87Zzjtigm*MA#(Fo_YQZvQl50KCs->3wEs6TA~hc9=%l-U&RR@-h7l1_1vSfLm6r>9wH>$G3jT9'
        'v@qi>hd2~;Eh}fi22+nUQfkPadTXrI<fB^6zO@;iM(^sXyrs3#<fzxoC(L9NWnx2Rk29MW1C5t8vtZpzbN~ly0t834U2<BsNrAI-'
        'J&GXLSF#Za8LGHT6dtgLEt}nq_pl=|l=s9C4D)i*pppTm9q-^`)=`n&mYUX16bcZjm_4V|fNW|DK(QJC^ida#D+9Gbh?sY%$IH<G'
        'PY<;QNm>vnH(GWIJZjOSIW~B(>g9&SBE?+5!fgOy0@nv(jCTazK7}zRH_c+?Nf)5*%92}CZ+Sd?HCcw<4_-|%s(gPR-Yqsg2&A)='
        '>iP3xL87RE)&3`*4FySPHXJ}!&juebLZj2);e@et@Nhn#;&(B7RFwQbULI+00XDEgqm}0u&l@4FXvxQvhpwNDIu?26_`A=ZpFF$x'
        '_4FXdQIbLk4a(sC!STi6#jpCcFZJ7&AwvR86adm5Z9#6s>7ETMD`SPHjQM0vK98uehEHoL^aTv&UCB-cVw4H{dP&BofPkT>m!Vz_'
        '%hLf3K2w7=NRZHYSvC3W7EUOu%J3%7CyZ*F63fT&6sq+2*5yLkdZ7t8UBa`~fVLZC&T5`3Hp_Ko`DXZd_!p`M$FXBq0d4ol;A^zK'
        'jgt88kW6<$n!aTt-pAXpYPPjeq|@#;wd=kc(7WNhnkDRJ0PADETqxt@&)HB8@G3S11L|eK$Ib}Lz|<_N`G8II1f^`rh8E`J3_=&c'
        'q}$O)8+N5^MHHrLG)ic6Ep5X*jP}cWHnJj-zNdU{#swOf*d}pAWW?$le$DfGL2rk{VZs(AbZ<7~%id>n$(sVA(}Safm#_-?!jz1('
        'e=#-!BxW2s<_d?-ML+yhSNW5ijjH7}>Ni#^lVXqtV4O?{CWu7@yI~syGE5<Kz_Pbt{^8Z((ew25=+%qEV}co0z_6*xY;ix&hjQIb'
        'x5VzvHz3iOLa$kF(8b0kqt|m~h!l~Z6w%U?RpH*L$;;s)yJac07r%$5b`z4w+-onZtbO@~SI2ylh|+gznOmoXvz}OawnZV_puEb^'
        'a?oCy;gw12vNTJwk1w#K*{YMuzQQ8p;u%Pa66z|^9B`5AFH#T$&qN8Y@+v{8beHy#g-W~p-kxF`o0t)D$<nFVUF=!X6E-YehZ~lU'
        'Uc;6mKsSo|@LtcV3a2)Vt{?G_A(_>)zD{Wt`HZB`cuttUj3%O4NIUw-#rs^Kx7;s@Ir3)3f+bl4$4U)aW73K#)!b`mQ$j8Ia;ew*'
        'RX<X%taX1yEp=|iR<o)JYlmE63o)Ead91D#R4KKkt~!N?YSv!oTCTPH4PZ%r(Wo_!w-C-Ef7FZ~HL|GVFnKZ%qxjP`;G}a0()re_'
        't7_4;0rj1+2RFle2UN;AFx#h$Iz4$4ItQ-aiHaA!Y%N%KO(LLI-qJHF&CtrTPK{*K>1u$97Lpm5Fk(O-HBW<sVtxV6u<rR{T#14g'
        '`xyRK6=lyE4c_MZUpj}Cv{aZ|+Gip_ZZ8>|ExZx|YM~#g3@_g+*rB$%!_#k7vo76M-^SqHj~URwmb!~w3(eY}SVok9YHtb}Yw>l?'
        '!bh{l?L`8dKc3vFFer4`8tct0Qrt4M+-8<7&L&=`b9QbbXma+t<O%!op@WTIa|3}Rb8aB;x?%k_w>2lG<qauJ@BnPBNYgC3#_Y9M'
        'r>*71B2aHLPKeE4QNT=Gg|Lu#Xbe@m-Djmj0+;^)Wgp-?r0K)Ov_i!f?9Ny5d0?5bl1N`OeDrH2LA0ys2UN$2(iIy3n8IBNC^Q#+'
        'ZNv;HwTr8nO8U-gYt|fHUXRKXOQekU<zj<AB>MWlr~3<(Ro{}8$ALwCEZJh)vc^_M1Daqd5;g)HZOeRl<KOhI$*ZOn3gpR88=&b~'
        '7TRV3jO1>u!RwItQ8SQZCRo_+@lS17KjP}FQ>&Hbz4cZM@s;(x`7)4KT+i87+K}HWEw7}PkiEK9*fU!vAl{p6c=(p8Z*&xC<9dTd'
        '7`w~a1Pt)i>5O)9XznJg%=j)VZ}X9DnlDO>uz5bKF!*R=HJNgm$~2KCQG}LjH6AZ(tLw2jy7>M*o05q4o$2iRN6mNEV*DN_*&b)h'
        'c2dy=HRK_=bM{pGL=tvKo%Uw(Y^tkU0o6eNLGTR;S0HitaJr0YXkz7ZAD9Ynlh{kUi<b>~u&8}(GqDu7LLAL*ydVtrqS5oDB3<l7'
        't6;vQt#swUY}-u01gVBZyNX}cE0`hOP-wSwN+SV~oHJ*!3N!gXm$3Xg{8hS`T#{n&(p@%7$2N(Bd@B_|%Ti^@u94&Lnv4}|%Bh~q'
        'YqC8tAg<S5EP4%oYP9Kn?TB$Bp~Mcxx}mbqblwu$`kMHk71ZBY-T?L2zSP=>gRJkm&jgRFm*6qhwp+2qo@p=b8@wTKqN_yg3k1Bp'
        'G8Fd)uWrfI-L~Fa&dr#*z4fovtt}?!*i{HrUP;~soT;nMw5yK$IH$V0Wii#&A$R3hX|qB0Qf`rlH4+r9YPBZ}Po!G+nehiv(N3g='
        'ITPz@^V69+nt(XgS)iA{@~OAH3%r$Bn9XwOdx()%$8&HMu2CEdx~q0y-+5kWDCN)TPC9x7d+Qb&7k<#~xxWI=B<V1BHC4z7?n+dx'
        'G6->m6<*gbyTTTEUZo&^&B((Ep6Yhp#5Mr8L@m!n5Fgt{OrZgw{frB}$ErIelU0^GhFv$KKpQndI~gUptp!Ho`3E{ahaWm3a-4qf'
        'a2_>k7;F#kPl+`GB`ofF9xP`1%#zxYz*1GcqIl2+^GdHq)nDuS*Byas-I|TsSnJT+Y4mdc=aaMH%fn;%`*i=}*-vc3(E&KB90>L`'
        'U55tHxwbn-8yc>%MI>UYA!%DETDA7}xZtX}RuGP>>9kPv1C}l^UJ~Gez*DV06`o8Um0vZ6nIONjxcee&1$Ie`G__#=ZgbG<{I<>;'
        '3w8`TlmjPYLofVK0D#puZx8>o>FkP^G8sCl8oukYRyOO>{z~Y1#6T5v>419MNrAnNy#=eQuRj$6j@2GbT3z-5hEbiHD$uMa+Uet5'
        'dzN>f=eme9Q^qCzsskswM4xW1^!WAruA%Az#!CdR70E9|^KjUtiE0ptS=m<dlIok~`Zq8Z<l{tpE8N1Kr{igl7Ejl!esCLGglEMW'
        'z*mr>aYc`Ian@C*A(_=!tSVguE-}v7!osMvjNhT>2Wp%2$IC~PE8OeU)QV%%Fp=7MN3J@wzRbGSah&v5o}L<4tW>m}e4Ohlp-40U'
        '`@Y5P$<o>BnKKjFX$MWHRo=`BqDzh;aOEhj{g2vB2cuwYG)@(m0FCnDr{a2KV6D#A4v5YHQ%%P}Am&*uR?^kB+TL(pOi%@KcF-rO'
        'iQ!77S~8H<`Oq4f0kOXZ!1bfo3zftH(*VbWxJuZg%6l7qw-@bfG<X$Sg2s?+ustqSz=<|%`OOngscp(`HWNBHh6RneS*111ck*~q'
        'PYW_xM>J_QHY@}gWh%v&scvA;i9F<Ae{$n}-Z$1Hc@qS{Rc{Ok`rbHmmMKS%DvWxzaU24;mLV;^IpC@j0X=-VniCgAs}cDdMhn#N'
        'Bo@Zlq#Xec07=w9lL4~tP|A$Uohv6Officqy`-a-s0bzQkZie_d>203QxMVfsI|{pj5U^Zm0<e1ABc|=2*)<zS0i-XdzIJQ8|8s7'
        'aY7Amz*mQcFQ~J3Az>fAb5S|EVW5JLA-Ynn!Q|>@l#b7Y7v?~BV)00Uessh`;4uq;{|^-ug!oXlVU6@ULw74D)J_r&B7CVK<ciDk'
        'O^KHaPDNct>bMOVvuW;h?NFs0pOLYB{K*&7q=2)wNhxQVgeB3l0_vIYi0CtlB!xY*#MlQHhk-&GTQRT}Q}qBvk-Y#P!jRhD=IXQO'
        'JFcTfSgV%quGcuIYdqQ}@Ft+@3hx`wHTuK$QD>_bbLy&~AH<w008O|Ea!|AxinZ&@VH?)43|>EmD@dHVcW~keC`x&4q3x!cQjG18'
        '&z2oW*H}z3Gp~Z_iq2O;gpmSPYr~0e8+`A@*idR(*G9tT&4cn#D<*&w>VQzgV@aaz4kpU1%ayz<wclEuL55h&8_8w`r@h5OtvY|H'
        'NXyGp?+?_nYOFt+IM*BQxLUn_V&#J#V&dSb9g4?Af#TX$;XvB(k>JL#mowNL!8%MU$8}k$915Y#3`1X2_$vY}Wh8*Fo@P~8TXg!&'
        'qkqF3y3K9h_O!2~Li(h(PhQ~ukM9Ldb*;;GD40Xq9%3<qH-U8P*SD6bz6A`8HVDerC)bI&BMN#gI__+Urcb6Z>TqgAODBV+fY8?o'
        'T|Zi@b_xtosnZ6c<?{yFQUY$OwA*ZWQIF3n4ys!KPmru0aSPo3Dq!6cPVE%uaO0Z}>u58<AiTp3FGC!)n|(>56>q_y5>_b00V)Zx'
        '3Y(sBaQ}F<Hym}LcuJ#J<_iQ#4?tnx;ic*p(Ma8d><aK6ry~G{6QEHAUKWti{OCXb#LDjtXGU$g8^gXW>fRDoDQC5)bR*cVM>A+`'
        ';D>%^cY4a_6mKgO14Ii;SjK^zulLqW23-)^0COk7or`7rQOEtEv<DOuAmz}NlhnMlW(AQSAmvsA!Rr@`4tw|PrshV+hV*B*_c15N'
        'MyH0AfJT|l*!}y5yFUBjw22)B`-VvMOgBu|Z{hgINTVvF!t_3r#m6?wmb2O7T`~I2^|ZPUhQD#>1D3M3+ek8LnEgfpZ$5KWY-?<o'
        'V+<F-&0uVL-bNwXU<FmH>NhN}xA|qgkAP#26?g&HYncfFTeaxl84*@sIP!hfpjh8TGpW@(DuV<ipO7&+x)*=6DlR5;`)MsTRGtm{'
        'a9L!x*2zN7V4cT{_A%kR6SU!0inO=i$<F+qvM#9<AEqf1_iU>@&FVTiWNbG8Y#$Gxw=pcU014=t2&m_3y)xSn`mLhTIMk5vo2>A-'
        'SL0dL^|Dl{X6UHE8j{zEMpWkcq=7l*VGs0S7(N9lLxz10y6d}j8vfHe6EUi3vkm=XV5k55lac%$5diPZ0m<Eb*2y|N2-S_KZ1J=S'
        '&2-U3_8dqSkWYNA?6T3$VD#nYO;JuLuxy@{sfyy;sDPlXWFLtjzbq%_>Ma;Rl&-}_zTOwM!XlsPh&CLGw%6A6ii$WKaTuu(H`CBP'
        'WLx)30ICZtV7`X#bs4I0-K!L?K>ldx4;VxQc1f&Us5NnAnFet|H@Ge?<f>9(!(3C^(OW+VI(37oz@wfLm$9Y>gDN9*59(1~9W-CJ'
        '4+(n#09o2EO`ZxU58p~&%zv_SkR|^2cyHpzZY|LzWl;$iz*&(jLg!s25im}KME9fe-dNnliF?W9eeBw`Y8Y;yR-sI5M}w&LuEYGZ'
        '*^V{~FkLb{%A|=u(myEaVE|POlzrV)(<Q72x%8&WI3;+9W1+1s6`8M_b#KSQO0PheSrv+MT_jMAJ!NjN1{s|SB|Tg13!si3gGwc1'
        '!ptGWP+`ehYokY6KV?=pOlO<uIPrk)W?q#IE<sws{V1qPSH)<Sc&XDMDPN~<M2<W1)!Ga1NG{m7mC%xXsh41$70Qogv2+^JVX&)z'
        'E;09w2b3z4s;mqt6|+P~1TZMMI8J};Re*dio)Q!#&I{#guIt(PuN|Yct2I4EUB;j?1mr!Tv%e~yQ*T;5BQlOx7e5aEN<hZ0dRV$#'
        'XEQ}8atRN;(lIaLC1YD`Bc$P#;+38N97bWyo{UC)mzIg|#N#fj(TwIAq?(yxI<1KhujFQ2&8=l@*kAh&rnRuCmcM}LW!z=wMPFN-'
        'fp=N$g2gc^uqPeowp5XAyEs#CNrU{Jgp>wkvV%>pE4Ut1mC-|`o*+GiM~74(QBSe(FiT!L^M_-!X$FGah%<`z?RY1~7unQcd`Kq9'
        '{xF<NW!pm8h2Z}M|LU``mLai&f!JQRbduQkz?T~=J{J{Ui*2M}M0Y8JF%d}EP5dj!%RlAfW`vd!>JG=s(?aj<8y|HR(g$_V@;<=s'
        'iZw>?M(rwqBJ(-f<QlURF~<C3QV~Lo%aw%>E%H#@oH#URlN|F=a$u&|1i<i;rL2d~X@`U5=;xNZ9Pi0cM<Ea;T6CI?15ipTnDqdv'
        '+IU2}4F<q-{4bno_xkOx1T^U&{k=|FA7*9bgh)3s2{75DrDB|T6nbSCHeAzeo(b!^L*~&Cna;Y|M!$N>q2u$3x{OpGD9@hZ7dW%J'
        'VZfBl?9DX0^*BlY@L|6gUMQ{XLsjh-Hf-&J3~))=BUv#g@h0abb!I#8&{?TX*!4UB&^|O9ou#~<1hN~I*ji^i+H~#PCndkJ^il0*'
        '0oouGVU#PzNTA#tHe|G{+)bLnvX4N@SY#`XTfmv_lTLBgDo&XY<u1^bT&d8dDR8w;4l^TBb^JJl>V1?oZ*mg@KrM9Ho)4CDg0&vX'
        'Q@XCx6s=pfLyxj<*-ZqAlY|XeAsuxj1mP&1Kx3#M*E$m;_~B=X_`uuJiyOvXmaLy}^W|6un<jgKlg`VE>ex;=;!f`}TQFWE5d}ql'
        '6H_~)BpA;eW);iGfkA0lR@=2uh7v4yrG~2Uq5yM2HXy1vyVwNb$L$UVE|?7&*mR7|EZ9w82Qs;A+$rLBt{kVzAvM5gnk`+URiBvd'
        'WFyUQ_fRy8#B%|37wK@Rj&U*R@;mhuk^vonRYh$Mq#M4TPIs#dwblZuDJ{If>Kt$$g=LMgh$-t7t^YlpX^vi6AXw!o?je~nt}&zX'
        '`$ge38#HeUkX-F<VcGeK>Ac?Yq=wPe=`3$B*RO9byc&JF_C|PArB@=f*=&W=DTAR_C-wp<8h3Fpid>bgl`n_~hk9KOQn^T%n4k&@'
        'IAqc#kWj?lt&|VYSjlFKmYxINxtl7qg}AOOQv}7X&CJA{?el8?!blEyd!j5Rjg6ue^&PO+4M`FN*uo&MVe9}e6p*5t;b-qsPJx(m'
        '<Wf^Mu;$z2VGWMyNk*oQU+WXcipKYp#Vf~=V!x5<H#iCvRLjb>xH*9B$^`EX)>DuIyNkyg=cSF>H6kwUp5eG6A1Ymd7_q5vfpCSy'
        '3MjlTxHFe(Gw4p%PiD?_xew#bX*F-cl|w(cer*Vn4WNI(%s*__GiHVqLRsP==2yo-jx32Q4BINbn==R2`I>ydrEBZRrR*-%wpCdv'
        'C)z5kF!0(9a${1q79_t(+X6gD&w_w`ux2X&(MRaCZpA>!294fzs(yuucU)OLhC6&>Hog?&tW5U9D68l;<g;pX{2wA&Sdz`ghwY~|'
        'QISMtjg>T$;#pe8#M-3ffgz<RtESG`jeN&M&KVu_loDr-QE3v{X@fcBYb*Sz$W+F<SzAE=`QFa5VgcCph1(!ZoDl#dxxk(i08SwU'
        'ZJpSbB8H#To)cfkiUGH>iV3R|QfXVU;a0X{Mc0v#bawHfwn`f)^Q`xdwE)_x_qD-DsfHdlp9ozFa|Qbd#U>M96jHat?fbo!AwAZ2'
        'jOTR{9T*<Ms7m@5c^?{VtPQLiZfu=swn<1VH%u6@Vk!`p8z3%TeZLVq0py(Ucz(1yr=hkS;ov+NISuE4G2y$b7(a%+upfhvN|up?'
        'e(2{`Y^%M8Mb`4CtGBkNu4re;TIbHNF{&T?c$GD(Yq%N*&CnGl+%#&osA*q6^wg`OUSUMYg_P#1YKCM0l3h1*wiZ!M7=5BDGtGa;'
        '(~pwqa=Bzhh$Pic%C-1r5$$91hyt%Pm~XnmETSN9a5T`W=D78-W!uEcNo-8CYG|th#e3_z=F+V+S@hP5Q;6;WRcQghar_Ww4MyXz'
        '_Ssb0=48!;8hlImGq@3O9dkygs;O{CWo=sM$X==_9?w-tg)c2g`fl4C?%sK|S|rnR1^W$H6<v!J=a%h}Tp3L<U>$tN!uDeJj45?~'
        'P;5ah-q}Q!ZqG{osQ_vRGPLkT3l=L4h11r!GOAbYhS9fTJ=t~m8@-VNHUnt~pdvn=<4tbjd*Oz6A!}kkE|N-@E_}uRyQokO=R%R7'
        'xZOZc`K(y<T4qVRt9(@)i`SnH_Mfv<fJ5E{QM2l;Wtxjqi*2ZZ`Nr6v%nxN_caJr*V!L>t4LvQqiRI*>XGp?b>Rc}O(W|o~fd&r{'
        'GEMvdw+;?sxaY)0-E)JDtJZ^@Rr|d2Tt8Ix&O^52d{R8%M@kG%;I&XtDyzlr_=|kVb)J<(0oELtAGv+(NK97DAaY)AN30Ph+GTgG'
        '{JPSXvN1N69omW3HSAzHfLg)5g1)CCVe5^NN82#+ed5AC#GndjVY4^QX4jL942VbcciZvRMC=Ge7<C-|QHIik^TRogB0usYYjQ>r'
        'J4+c~qLErsjw8{?Sz)Fd)4pCdj>cys+s!qB8b$0*)<&)+-I-48X_4<qC(5k?zGvcSjZ>rPF&MXP#5+g3RyvNJqg5`VmZ^?GWW)Wj'
        'kO<t!><#i@7AM$`cB+5<%U_ap%ba+>wBn0t+N9g=Qgt^#;;fofY?pP-4Z{3xdA&Mq^*4xvV(}qu_5>SVpk_vEk#(R8zawnG_&RaD'
        '4yQafrh@K+-}p_@lUI2qU4n&CE@<u?5Z&9K9pTq3%<(wfw0zHMyah&f1dkhv*gk>tgbcHghwg)fyKK-X8tv~VMe|1TQ@+XZ9Ud10'
        'Wcjsn=Vrp3G3Hq$7zwmKzB)<&{p{rU=-24K`03f%!TyDMIQWleN4N&RdGaJ_Ck3HPjlF1=&)^uw2P+#0hsZ+k@q^1Kt7#&T2o=2>'
        'Tw74k6*r{={O3|CjSTgM$~Vn@IflwAnMavTrxt)vf|Kp368Pe7R!wXi>{L&1bdulTp$DVmLv~l(-XR$lE>iKz=s#p)Q7X;571pyI'
        'U{lW=sz%u%FvP4u%gm->F)}OtQrVy@AFD8z%)3u1v%Rxar7=-`tF$A*4B^BpPFfvNvNkq#x!fJO5j&*H#xXlo7ke_Q2Zu5h2d-?2'
        'JL5hQ*_qwNbmlSldRzMZF4(0(oMIffxT7{Vg&q_o*5!W7s(UdpQ{U33cKATVk0wL<kL{O#?-}?KFZ$Jl__)o&32DMitY24{qs;>e'
        'X|OpuOqWK*=`C?=gEo(y<DP=RLg3B1%}BsFO^|bFZ~;_<Q4@Q@a?Fq?{&CZaa?mYq`+%v7##G`TcmU|nuAw<Wp<s5K@GS$}uMfYm'
        'bHq<i16wbNu%XxUT7OAbb!r3EU*YU%QZjjnVVdaRfe2bRPZ01H-fXu0DKoaREvRfuJihO?VPvZguL|Csg$m>4i|&X`99ogVd!~dK'
        'fh4PyRn(1>X57-cvJc&LwcuM<w{6Nw+q%aOtHC!lT-GJJ6w&Lg6X07%u(favOOvMP;AXnCaC3Mdc9BzG0>(+~CXbscm6)j9<<n^('
        '6=$y(V_5z;5EBE_=7>lbhkeJf#Kry#;x9V9)`OlRz4-j()!DOyl+;<>n;F|bJv({+>e)qladvPJuZS`d?nehAJpXB*gNfR6AsrmP'
        '+&}v@{qgYV;CTO~+leJ|hdWTUfB5y{;5;TPUn>fS3d`zr)B?o{jGD9^s>xxPjpT98{N0MeMf|AQ6PjtNBq4n|mq*i^;bb|RH@!cV'
        '`PV3lt2ed-(kL8<#1r8=rf!vgP|M0;<n2%$%(L~~a+2v&{K*u?+i=p6pge`U76y3x;4Pc}H}BIA=4$XevFW(-q?K*g&nQ{vKm=Qt'
        's&CSFa%Cs9k6)K`89XOh;B_qz*x#IfB6QXX)7%3oS^$i-$ak@1og7h7QNE9C{|r(>AorhPI^--uK_j{bhW*X!GVa7?Egshbg1-Yu'
        '#0xGMihG3$=UD}kxYnxjvkBGMVuI(uozvZr;&`ykvb5d9VE6F<ZR2!_r3$!5*~%z;u+zl3_fskv`~nkgjJh4GY_Ugg`+ac;R~?30'
        'q!g-@tTJ@eRZ_HWUAQr?0Au=+PqT9-);!RqKnE>>ZWCvBubq`q9?e9o7-kC!na7SQo!;#VJ(pDB>H{G0+&vJ>zUzBcI*Ni;NMntA'
        'K6hNa%R*nXPFvf6EeT19qaN`1u#KbC-dNLgYs*aP>}KKJrPzJgk|4e9upV{cvaCq8Mg^dEt!venPFolSn-mnaaPu5r$1U3jrxYAF'
        '#}rz7!*D*UZ8$}fV@DxsNB0T$zb@cU7<klbJfpxl=qmLw&Jtz_WG$r~_0>r5ZSzn$&|4B6P_GjZyCd7v#P=igCQLRRAl0$M0m?!#'
        'PVJF+CwfZ<i9MYS&S+@wH}N`1xpFJ|%o>wmYTJg@Q9YtwRU2J$S&$#<?B0XM0siu8%lB4Rac{Ux8#wX4!eTsT4o2@eUvbQvF5jyT'
        'A1t9l#@j^R8=}<uWN6eVxYk0X84arW`ZXKBG1dZt6pvD0FBj3fOfoM5-jRgmlduz)vBeQr8GAqmVNLOekeHrk2B@(iGW0$CC&e<G'
        'kH`<+D?`CX+DDBUg*j~6V^R0i7!$rV*X?!EJ^e{*g?ZQ+ty>lZ%EP9Yfs(Tqr_UbrQhU?{r`*Md3O9p9!IDhml(l6OyyW4xCT-6b'
        'PS3I{DaWZpH_d@eIV}-M_(R_qY$Eo2S_SS#l8?n9<5c&0UW^1QGQv7R#?_Brp0>r8p>l={t8-;1*~U*I2%w4HhJ5}9h!74t6y&`G'
        'mQTfib?1Q4vFIh0gS4r$LFkr~6$TsHcQe*R#kDbPB}_0i`0gEZT)0(Q8{Jd^Q58L}0{RY924$2&H5yu&9hx%Hu$*7)U%WaOEsOZ?'
        '`DLD;?f-b86fyClUIG<B35DjB&aYTpsa!RWB4sV4SZ%*NJU>4?ej%+J%Cfn7hkx{GN*XqKkY9Z>P3Y~UR)aOwE7dt>fk9n+wZ$}Y'
        'cuD`R(b)WHP5da@v0#G7-S>2Is3Cm)@opF<5P@w*y!AOWD^UVWK6eb0Eid!N@#yJ$p-(t|oGLp}1pFypzO$DV8EZAe=;AJiu9W|W'
        'T-=Q%M{(8U(q49pmKQ3#Xq^*^vMDtETXo%6er6JFlP-3({acY^v#`GCQU^MNIiSE6q<HB|rbsps#`@1tR?Mc+9ek*6Zcqm^r%dBH'
        'r;`);p2LF+beP#!4U!BCqL<m*yrhxUSay+@bUE1@vU&b?Gp;cv>-G%GC2Ya~Ct7h^5@u&AZlH=VOzn|QpuzPcAn^S&cEdocU=r|h'
        'KrIi%rrn-4#HyspDamF6iz91}Qxd(amQzFsDwXr0-7ofu%R(TURg-+mjF7?5xSHQ945@gi+UA^HKHyl+$Jw%BYzusZGjhS<bTfb+'
        'T=LMJGLgn<<Wo-p@Qz19P>c5hR_^Od7ZdFl{ko`JHhq`fbL_mQvVv~`iFH=v6HXY~h%_tG$vd;c=8Es`3Jk!<wR9047%5#D)5|%X'
        'f5WY<vx66h=ND)DXX*39vo2j{O2&|Bs@0t6K|_-oTMh690uuH!0e4^V0jEgm$HhD=7en;Rv8z2lIDd9_czSVy-D6<$IWR2cq9LCg'
        'tK74r{qu7vvb~%xiZ?Jj+p^NPS9S>#(lmBkhc8c$4qhG{Ur^{FX>_O>Rfqa;Rik!uKc1Yu+`qtPu!#l8m6%AK>kc$CwEZ~VIz2o2'
        '`QX__`ts!Y!I7XmmMI+2GIrWMJUDueWA4Q&XW!zUBec<wa81{-80G~tdci7U#g-U8@*cuQ`jKhEipfq!YqD#EP3qIy8qks0EN^a#'
        '-xCYQqy1k`Ug6kTCqSMdvAA5pKlV>Kvvg)KeEC~e>tg1uDXM(IOi}BN_0!Z)eqW}B^G5%)z^P^fBzJI<)wuvX%$l(WKfUtD;8uhV'
        'or-2eZe;<yvvPQhZW}U`&NjlMR&69(`)4mt&gi=D09WC4Oo#9fFtNwc>5J3o{P4vwFj!{?^d6Vx>B;#aY|~%Uqr;bnIOTuYeH?xF'
        'UG#WoOBYUm+&?^me|gpZu>TysV7~`vXX@8~oSI)4T$mru4t_BY$0zCOs~_OmxVV$ogt8-Nr*L0L<Mn*+q>}r2|BNUe(!z{<e~a4k'
        'N}_yv`ee~&>U7383&(HyK|)*l-z~Daw(QiI^(x>9s)X=O@^U#Jh8XFr?j`FpJ95YzQH|!nY~-~Y%+NUO0W>w8w$%7wj2arecnZ4i'
        'N#G$GB>qm9^>kRXZCJeRIz;1lRKL`V2F3GUe79K4o6+|6il}AMq|wCL?h9IhNDak~F$t&h^VH`A=AwF&muX(UwZgaJE4HhI_eEWm'
        'l7ZANPISsQ7@@DLOhmZ4=lCU^%<%Yro7Dv-K9EQ#97mW?ZFg=u_Qx0hkOLo3N2o}r;veZK5K=gBvZxPGHW;_3fUbSZ&G~_2p)^aQ'
        'R=#mNT}QWF_2(!y*sE52UQR_)vHK#yz-G;|g{b3-#Z%4fTcsRsZ>Zg7=#1Wr{_NPGBR;X-I|*$G@{lvD7~87o7}}}au^u)K(MEni'
        '+Tk4KLp*NLtU|$v?+}v2Q-#CcV!Ur#{|z2BG6ftJC^Qkkn9ZlzIPb+n>~I@<3%b%gBou1c7e|lNRdXtF_qv*(;TX|?6!8pW>PP+&'
        '*~g(yi|ZkooDSiE>~cA%_z+}<1qC143|baz_u?<Nzl{6tj@)yeT9*_i{L_=2CvAPJntSbE?kI0M0!cmI+1aEeqd;ig(;^DVO;ale'
        'xfW|>Si2p7BIK=IX9d*xF$Xb?0$KNn;8CC#iogdPKm!{TmiJE=7pE%YpLqD&fuAHawrL3jVSKsDS|X>=+14pxC6*Z>5It5i;5%%N'
        'k(<1xNQTgMizGhTQY%d>>J?&#WJc8VjH1s%RKzmg3L|Dwe|lHgcNN;5u^2A?3mrKDNyYHgQKuO4*5RKYo22)$dkOodp5^L1C65O('
        'bB?}`cGnC;sSoW@knnEXK3L?p_oxXZPBR7Uq_)^1lOwtgvF}BcU5J_z2?&uS$;Z0OS$fR_ORbrMG+t?|ci%XMj4LgD8F{iay`sdh'
        'B7MsH(^oo-)tPmav>U0qclmS<)5al6SUDy0UaJ;0H#8|4MBD=JKFc?Q8+2MFolgEvV#<nBh!ezwt|~Dq7<L%<rDrnDL%?H(pPr?|'
        'SP#ZnJ+)oDscmK0p~S7oG70va%#|6x>s!H*;gr!5wQ0Ig*&-}AcBL-S4l6+qcJ1u0THxHC@~|=i7@A`tdKF8Dk@65a0Ux(7oRznC'
        'utS^s*>#0kl&O5{CQuDn<A}$qpita2z})0>3LhjtjeDD<Q|a9lH3~FY;wDv5J$Ol2ntWVgdZueEt;$?dTfj^1j;>m<A~QTAt`UUH'
        'X5_D-xCP#|{Vg-!*2b|woK`@crrF#=D=uBZE)W&|Oj<e#j{u$B>oreAMbN<Gv(V%&qTVJ8+l@0H3O>QBYt>qL)YzdXWd6<gyl4;F'
        'TP?cJ7ahSXv5bPHXwV)`J;87CKJ4BHYhm?bRk-lFAuHO@bF2yn>LBLVkr`*HuCo2K24>F`T3?1{%Yci4hh}dvR53wlJ^+B1+$UOo'
        'qH<phM@8Y<ti~YeVpEO}iu2A9<*+NO4>;Lz6J1wwJApUoq+!=_MOhJIFzLp=M4YAKXSP1gwI$La2jkozKj_Xl`maz6Ux_MfO;a>2'
        'N|wY@&kl|+&i0Q|j?3e8M5z3ToaF(pOQ?Xc`GtvdElDM%w##{*oO_vAyacj532x>>oJ;;-P4+k|N&S(FEktMP4gqn4j0Elq=<J!c'
        'BywX;vOUXZ2L)RUpQ0}{x;RI3qeWLJO3$f-rQN`>uEl09+_UV4V1Yl?7r0T7`E&cz+@G*`f|`Mq44!PN%c>;A`{nhD9SBzK@;V^^'
        'N9jn-Z~}Yjc77Y;(0-w+QJw+gH396A<&<uS?N6Yp&JvGELJu-5f){I;@Z<)5<TbIHeS{8GRmS(&d{m-G63ioinSIp8yJ`jf4&@Rz'
        'LYE%m>fH3i#c0xy*oLR>7sq&5H@>lxE5(Xc)ojMY)~T-~*t)hI+onO_bo7)m+CP7GcsR)FS%op512ev1$njR9D1c>*)4ROJUwP9E'
        '>t)%yq*WIW2G>eB91O~8u%HxU@S7#&!3}A3cioUPo2W3fJ45G$aY%l@5RGu7)aFQB%ROsY&kue%esy$&%}#*Zw?Ca8o*vM9kl+3H'
        'tz20xvK)wYH^;=A+1sp`D#8q}EIJG*_AEAi?_b9{<`SJM*pIu%+yZ%7ka=A-9`b;^9A6wJ1Z8Z`1w4Q|6t}4dOE-S`;`r6+i_ujS'
        'CuZ{jYea5om+b6xxy#45xT}fI0YDb{o!^uEZrUKX9n;2!4Ll~=Z@R@`*uU%_9nziBmE(1eC}DK!)W1Y}U!0s!X?5GdgF(s#yxKc_'
        '6S%peJSe(!61{FKx@rQIhDGAr?q-|a$|NAWU59|>orDS^q}aDrWm9&PK$Ph<v$eD-*6e$mniC|{`Q^^l<zV+}xCF_swlqnMdsAbj'
        '3dRfxfAJ+WCrR^Z6fl`6{m-%qntDW9U4@5H6R&lJcg8?!5QNn^r;Hi0irFf56e+R>Mc{ySBSGZF?cKt%zK~kD05FANJyNQ|_O-cI'
        '0&xBdKR&A)TJ=H9q>Y11>HkfElC#~vk~WA72}vo6$&~n1mjV&;&vKRDHskH;Oq&C6P0m!|ngLunBEuwS)1s;-r<@i?<D5%%RV@f*'
        'bz|%-f1gdQI3+@i;Tv9Jk(cT46w9yVnay%A@Q?+CV`*YGA_r6pvdg9lG9m!*5VIs@(`nCYZCn2|H<8!zcb`2!d3N#Z=>e%}$wNwr'
        'h^fhx#3MIVZFLKKm%60bBFtP#VD$QSn9XtP4M`Rp?qB1W#|x6^j$nC>@5j^JwqJQzKZCo1LiS^4ZPS(qG*-;!Jb#(VY}93#^N~~P'
        '1e3Lek@f&cXRI)zICYNVW;*)~I8N55PK)ahe~xD27)D3Ocs4{uRFJk|0cKTtD>66gl7Q-5FF9Kfs#^I1kxGgp_UHXGO=6@y8+T|;'
        'v>o~OSZv3v(d2W`-PMg<mgm5whGgO1iXXT#0&?slB-Sw?am`p@uFaR*CNcV_Z(V`rFX>%1%iFi@@z49e?597SygZ0a*|DY5CCaKB'
        'KohH?R3<fJ_Kq>lyAClTC~%7sfZZt2P|Tr*$CjX=dT;JvAxwv4&DT?XwHoc(9`w<_{wEiXDVnMN{QU1<G}2YUcVq?17mJEEEr+qP'
        ')^5DwUZXWDcwW%iM@&4a)llmt@8fp#H2K2e({^^0MSoC0A(%<-P%a(tKy2>-%Z-#^K0qP^yLn;W<j5EbIw<`ty)CPESzWFcU*f@k'
        '0HeGZ4XU|LBRCkNve;Q8I2Ira7(VVx3L-a{NbCVKLVV(NzgnL86mrb@#dA22?Wt>V$m_|gtcSS2vCtu5qy~?7c6RJgQmuu;El(^@'
        '2UMz}>1BpwF<n#|af!b7XQe)0{Tq*VDL1Tdr`2^2(N81BILWlj43DdOh2dX%rlnU8L5E2#(#jmkT)NkIUw55!<K%it_pW<UQ4N2<'
        '>)68+6=V-JJAV{&9M13`#r#Jc78eIt-T^@R<KZb#?jMi#FAkn3blqH!?*N~pmU}~A{Ip3m{_@~re{gvG<2D@5zb`EwhRtyM7HM9c'
        'Xtq6YHI2^ULf>#+(OZwq0a|wi0ytl@<KnQ!e^}mBJv|6mEX6F}>+OE~*PVW}OaJZgf5`b71`-bN7G-Ml0s}s7$(+TCohWwYK(hho'
        'jC(vn=tkbVoP{Dz@IY0y6+iH!WBn<D`AK5^*&+Pe(f&R>nx8M0u~NbaWg>9(l`T7l;O(<fe^>NAW#n)82~<muBDOcdSVWd>JR4S%'
        '&6Vv#pcKn2|FKEDGIFqD`js4VFaA9GcZr2~eQ#1S=_s-Dd+qrkDskLst1QT$s!^x}rwW9je;~!IB-}%DJKyTu3I@C9`nB5zY_`bh'
        '6?27AuVWEN_c}c0(Z-qhR5)4p#M*jXEz89o+t{FdBeUz1jo6y(A>v#t9-KAc6e2odHB7U*vRSq_BH$g<Bd&SXy2|dBd|_9**V}r{'
        '?k1xLh09P(1KNPm9aGy<pgVpB#aOYmYD-$2D*<P=L&r!)Hl!6U!*tK<{VQDXYGF_)LLJRWa79`<71qe;^Q_oNK(2efD}#J)1ouKN'
        'sZU39AUi1~S+>_9Z4n(PN(xA$?NXMF*iKIK+iZNV*<K(J**%6@m(+sjBQ4b6>)~IwzaD-YIZ;^AS*kJ22^#S+cfr0)`1wmVcuZK8'
        'dfkfIhjmn{1{Mu2ubiNh*hLr9!8f`nRj{I+yCo@uZ|qTVzFLYzptn76ldxg~mQ_O*v%Pqo`_|6z+u@E98X6CUAVNsSIx_62F~-6h'
        'e1o@7{xx5W(@~L8A<~bhx~<)v2yoCu;;uV%{O34$E&?<I+KQ`pFs;Vfl=zguVW<<c)Oiz9)w1p2FSsnlb}*Y=7q`o5*+d3;c%<9f'
        '${<5V%s%#WxpS2e^98m1-tdG)FAXFT7T-l(ZTo5*Yx=g4purje&SX(w+Aqz9XU(`MEZBrPb1-_Xp{nEmln<|)FZba8-s|(P68QID'
        'V)8LZm*wHhS4S6zM~BA;iH|?ksOYG4(&XXIaysR75q13a>*3{JzrA`rd_DOp*2c7ij&(?1md$cLr_Dy8yTlJ#ni#nOR2xSh*pu6t'
        'zSUqq`g?vaztlASkWFN!l9pL#X!EI4j+FIJ)lCi?L)uHjgR#e(sZX|@w)=`AYRn#Z&MUu2U^73GgO_{+SHDrM6PHsmlj-T3$UhWI'
        '2XsfDEZExrRDd<dsBvX|EXlci`YTFFOIFgKhuXYYmD;wLs<dZ|8Hz0HH;Lzp9s0l=3naY2=UDrgm~WmUYW<n&U;cB5=(T1F)U9i)'
        'u%!}J7m^KQ?Olvol8S4CTI2)19krb$x7jz3JVO=pmiFa8W$C4Q{|Ze@l`Ed;Vyu2R0;Km{o8VoIT9#e)q&H5Y@1mXG9}@wT7vIB+'
        'f92R7V-?(v?LJ<=j@65`W}U_ifNc)Z<(qYKd@3qCTbdqn-687$I*9Nbh1HCS`Op#)81e>qz`NX!S7f+HFHoXA^R2C{V?Fq~zYQ-s'
        '%PM56qCB$W<Y6@9bBih^I!d_2=a_H`&1+!POfVK)On8*Y!g(rY;UKReW1=`1;YH}1>_v9CVc9X5q-Iro=&m4~t^Hkk`{V(_G0k=y'
        'f4Y1vj$&2}R4uKn^VycOB+*xQVYRKC5}t0PH9Sojgyk`W!KmbHjwsxye8s)~x)ggY!f}FjCm64CNV;LeNZGogO|O6y;<45T9)?c%'
        '--~@<!k(w6Cof~Y1n}biYaM}?C?kJw6Sl2(Ox%;xgR_f69T3-GhUwr>2S-pK#Wc8F0ELA>L#CVwn(N4uinldtJ#7B`V*kbA@e3s4'
        'E~UlU6agR(_4oYX$AhyoKt-@7uvU3pBNd@uPF`J{zPbn(RSCTVeV_@*0g9-{FAmO5&km0-;=WB2Hwe4DGF}Z%d-~?BvTWHTNDO&F'
        'O8L#h>Z&8Y&WRFvEapB18?o4vQG{xzdlfB9<;z-@V>o?oB`6ygF@zgfq3KkS@8<s>o{}XqM|DG`Z4wf^$efx)i8}nA_)ywbTNu$w'
        '2(~Y}b((FtQfwi51rBiLnQna^dm<J^olccLW{+#oEjMNViuZzX4=<tG6;G2G5<Q%k)9zxQ!kPC|s^{i)j&>`1k)zroCok;5Mz%z9'
        'q&s4%_LbxiOZrjpBSq%u`e=pg2;83pt58gjM8XNK8u{vE0fw*OtTP-9<k7?!ypSr%)$VmDDL+B9;Ez&&hHP<N<tAb)h)v0*z6w`V'
        '#CMKVq>w@`a=4~~14oKoJz#BvF@+-(2O(9VTUIjK+*}8&9|)${cR4mCY}O_ISp(aoQ;7v!>GH{y=sZ`9i2U4yIB*fE{PopCM@t%B'
        'rnck{bSozYg-5VmR`LEH#%@w?e~euF(XO3m2hV;wc=q?b>P_4_#6L2JJ@U-UY2FH*c0tmP6Vr?XlyP}wNdY_y-<bijQIN_e*&J69'
        'AOBqTi$=%!QW|#(#5T^RQ#4z}fMyinoNO<MS-*k)(n*1*@A!^Ftlcv%*hWfo1JU0Pe%<?J|LE0$h(yY1I(x?+CJ|bXCA7vkJUw(q'
        'A|61rh<1n3alRnwfbaG&rWgC(v+XQ7>QkR2|J4#^Ix3|WtI+Vna7&QZ<f2h0*u~FfsNy?-d{Q9$*B+(vh=IMwsS2B??6E;2{7i~O'
        's5iyvOj>_RvDC5%h;=bV443&k<{iWzK&@z!X|sDM`hWiA{{TY9cWACmj$ERfEEdUd>-ph1Wy4JmkC7+l%ffS=b<$k>>5L9Ab}>@u'
        ')$!%d;M;6)bM@ypPu{B|i-QMh<4npk=~mhue0zm8um0TW@BY<Plv@~9iUsQJ5B`x2{=kan=P(^y{rRu`Z@xCow6j#Khgt0CQgvJK'
        'kh*SOjd1xVJEpwJ)8Ulu?fjR)%$LdQ;kp;t;QOtk6A+n9Y_ati8z9=hMznAJaJGL8bN_(K!*rYKfBP-gKYV_0d~x{WuMca18lVZ='
        'I(v1T9zO4??yM8)@ZcBdPzRRUm9SD<5~?J{EHM2@J)Is>%(j2iQQ*c?;-YtlkB2)tfZDI=^OI+<(3mvExIIuo#r_el=zdtvXTLSW'
        's=nRUZ9RH?gRN~n+rQX{8F+P&?w=mom9u$aBE`X&;;ok_&tDx;x%3FeL0x_G^^X2vGTu;9R|@%d2RrET?m%Z`*xqdy$9PzDCug`~'
        'WZ>}nvV>(xu>mOh8)cnKdG}+O<~Wc|6<#6#i2qJAI0sf3y@e8{_B*ny`nh6FTzr)}>JN_BOc{(;^vM};V=tSHmHZ~zIzKr3<>0x>'
        'iQBcda9MrTdp+bI32uq^WYSBFvmGVmfbNelVv3QGk^YtqUPN(>6%JoQ_o?K)UgK$U10)()hY=lje96Sem6{ulqtR4AbSR=_p~8NU'
        'yetB{&bx|of3Ld!O~6n_L`E9`-+UzidYXeYmo2Kg+3Ur9#MB7IkGyP90ZWo4s|%z*bv~(E4W@$<D8jN~R$xWvKkW~ogi?!o=bMU2'
        'OMV<|-iE$`2CBYjsPkr=;r%Zhtk{KXy~ANjHQHFoTJv!%xi(QcQ4C%wp-43>oNnT&<U7MM%@V7!AlkruE!YE+xf)<dNr?d>Hpq!n'
        't-TPG!=}8$TYEu`H$#G{mb!3wm;cTH_H=4e|IiWj97^7kYV6uDh~_Vg?v`-CY1Im6qoL5Q4xx*P%8iW81NyTs@>@>9Cc#|MU=IU3'
        '0Iar_CXid98cRHP%c!??JFCW_uOW2US(P_A`uY6iID&-=qZHm2c(A%pCJCqssK#t?odV5$)sP%)UQUb-#U4OoSCkL1(Eohz)U^i6'
        'AG?QY2fHT?VWMubL*=ZL&{%XM1abO35~-0_RoD?uFz$j);R@3#v?0Q;ZO|V8k3#y8>vtiD(iL|BfnO!(idt?T0135XHP8WySp{9t'
        '!0Omw;YvF+7xqHY3z=7J6MKQLCCfQ1h09n)y|>sqdhb}lsm+M-wgJ()mUH0II#12$M}&?`3!#+FO&0pd>;-p`k7#ea@1iwAy$qe%'
        'h`OyKSsT$kzG>#DXc*p%!=jlyp_NXH0u<>4CBPdzjwvkHIYw7(BuHn3MWMN>VS7uumm@nab<~p4&wzB+#5!11&awZ|Igi7k*x*Of'
        'a#NKQ!9#AC{7yOAqj=`fM-{%!4q-+kq(Ycnrsod4&HGrb;iBQ7^Nq0}lW%i4$j}uv_zezWQZI8#-X~AKb^}V7%`j>W^GA$)Z*M^Q'
        ';BO~8tQv{l9HJXp>I%+7IJxCC5QBK0OB}Iwr1HY#O^X@E#&)pN*YWML`CKV5wsB>)g56okdtt_399&o;z7BoRvm^B-!|2KHzl)zS'
        'TG7<Gl*@s4&>SE0a!qU8!ny31UK~s@bp$z|a={>#i7+$=$HK_b_9i3$QPD(bjVs+gb3%BG)z0N5T0Am(%)Lm2P-Fve(rIbwSZc;T'
        'fS4p;xX@XQm!BQ6RJD%$g)%FGT0@0C%RprHwiQak@$~d=k{81>{+BEAu$#R<V?1*P`?u%|IkJOeySgXjUH57Bp%gEJ_kQ|ZM!D^H'
        '#2sgoNowOf<<5KYzFp5>LjgO%&t>7tp2sc51ZU6ylsuh;-CD7!6ON_x6!l0JHNuSnv=tRm<2yD;z=npd?VyK0cqAVHdG1H((SV*l'
        '-|UdFTAjlREgKLy0MHVs;_MbC*>&AT8+?+6xG0|0&@4Ml1MR1D#p)wkjnn5?OjF@!D7O{)5M#s1+90~CsD|DV8EUR5u%I12R~x8s'
        'R82hsvXLZefw93tkvOeP%+}B(!G<JaM()8z3Y}sOk(9}x?LWk25RU!WilZU$LZMDjr3Wo)$^`y18$~}H9qjDvMg!Tw90Q?=-l6C?'
        '!@#e#q!}nVY$bsg>i;^9+wmh1C>*I{Dd`Lp7OGfC1`H_MCX#_5o0@QHftat6@G7i_=8waaeBaa5i88>EKfmX5VD)jsvldN}G-B{*'
        'z{Mjgy%n|Kux>Wlm(xdGzt6LxR)TIjl`nm5<(TQn;;TGbvPtTR)jbN@?8m}-i~j18Clm@W8!gdzS@U(efAN#-26GSNyONrAx-3_@'
        'gys6#>O|?2F=J@R6d`aAZn|}@!0EMu*30FHYJ1-t9?4tw4kzAH_N}`2;eO%7NNN6cAZxo{TG}XB*vrCd-wR^r0p|AJ+TZSMkU~cC'
        'MrH3jFGzISx@HR;Tu5+cR&Vy|oZd{9%+chezH!Nz+L^C_6^G`YQ={$OxGsz#qe`sqQem3{*pjj1oLB<897$yXhmReXL-v%oTC0UF'
        'ZS{0K2zSZ5As{xa#N(5j6L;Zzpa+c;Ex}NC&?Pxa0VT>(NWi2eN_K`n^P{Re=pAP@8A||1AY*YfBH;+ederJVR6%v+pUB96i%BW>'
        '$AW;=!jD%J$jzfLX9qvV8)T!rp$vO4cNs8h?)YlbAA0!)>m@QdH*q_H2*w#Rk#aGLSok(dWiS)X+FBUhDZt$XV8Y*<Kp=fcj~(!A'
        '3W4P66bRz1luJrmAfo%j0RjTtU-iy16WR>W|MfsXm7x#UCqtfK0!DW?P%T##Mz1zW%Sb&r0nDuIs%2FUD6fF%zK|}rrpgH}8XhVQ'
        '#PTxXBlrWs(-N3m!!E4w2U5@mQ|KRK)UvblRq1R~uvtEPoOD6@oS3{4mFE+-5mPw@mH5UX<zWNM1&Rb17f_Q8Vxn=2vZ>SS8t;qy'
        'k#jvr>LvU8!`q)_1to(=^^AROi7DgsC*5qaihc(cq~K}IB5)=jF)9@7U5zo0>j7#}@`+OY<8d;}rU;TYLPrWa91?*Ip^a?%yVHh*'
        'arhxXJlMwMu~_Q8bpo7h>Gbm<h7)g8C7w^qgdtwdUeYn4iYn!&T#~_|AW_(>vTYk-#uz-KB)ZT|vyD=GvyG0sfr;o2)DS$v@SM_o'
        'Ph>UEoPD5!ZYW<Jr~4Nd2QN=A1lrxW8ye_1Wn&e($Rw;FFu0t7GDf>F`PxGjR~KD-JtYMWmSc^1MlsYk!JtY$A(mDyDd`4NlNH~<'
        '%0e7$2@6}MPA4=MXNNCd9Gs!e_x>{g%7V#Igw6~?7)h3<(v;O0#8W22Qw5{^XOXw~G>oRE(DG%;xsgJ1diXpxnRbLGITj4In<F*U'
        'Ne1|{wf&uLv^5VQw_MJya~)qtv4I<il_4h{ssDi)e?>;V?8S8Yo*V1_)%V89RrHlZhA&Z4R>1}PHaC(47@iLHrrl^vR15JLAS!5|'
        'j|7CIic6cMu)BnwVwpSdT;l?2UsG^Xs@7V={nparp7qA-xAw<X&{&_YPL&OXzH-5PmL-NpGurLbg0hdQ9G~Luu5J~@X(>~t+4nkX'
        'm}*JiiC9j-`kQ^p*|ajft7747?-L5pq$*QWmUWKbmMRx+>qBp)EsxiGJt|anqWlG9f=r!}AlQ&O$-k?y#nrWLz~pjkBeqUEVXDfg'
        '3$`Ld;DB^eIF!#*HeKkoYdK05eSOO2;EtViH4d9eckTH0u6Jb7minVJ*-_BIsvrH8t+tGJgk@kAw^X7llI(;N?Z{C&(sORd+eOu5'
        'Af--g$Zkrc&jN{z9^A3y6yo0fJk&t|TA1eD)Ik+2y%WyFhbx|1Q?O3!^uZIOHlxx=Z8|#+V#MfYZ{-bl_^xw<v`rdl-Rf#s9XNUS'
        '*(Mpdlx$-~3TN0dB(X~YF?t|;65D7bF#?3%2}kj$`8Nf3uhVKW=^hU|u4Qixnk~w}d8}7CRXH`oiEr$b7~G<-#@GZ4O_|M`J75jD'
        'wNa#A@U^aF83Im!oWJe}xNSBfdy8V(p1OT?yzr1}qwhnx*n8Zzz-yb@t#&EpI|W|WLAKzxtmgX?V3fpv!lWB?Y&l{|m`4F<aKinv'
        '(w?@LMyXR$TBtreV?%j+IU1mzUUK<SnhkYJJJ5+xNxf_xoP3+B?ZQL3S>5m!XhyD#|0EBT*mx+O4kLN>^^N!<yEW0eh!I-6t2m$a'
        'gsl7)n>Zp$hpWzEHH6<NfQRGm$G~DFG&|ZUzx^<tn*zyccSjM9)I{`yljjui!4Qr1gvkspH5P3>qkU4VSiDxNcAtz_x&<8FQd?j&'
        '_)v~GYC<u80Rqx#@#TOaMK^?mM<_a3goPKF(M4h_c~-_@jPWYN;Epg^MRR9~s+KA8Iv}JKA0-Tm);kI%bzGEGb~5U}6UN)r95nG;'
        'tr=0lXVyY@+X0%ItLzpx(_2f*ck-O_E+gsG0*l|qO0&5vE$7$v6mx0zkX$>dT)1<IQLI92h?sqWF01S8-lF*$Xe+6kO5?}hT6swD'
        'vvaPyf((8R3o&ZQ*o;c8?Cfw>XOf9m$+%kWxVaJy)(UT>+R-6{RPJk3b1Nv~WTWgRMT2IFJ%bG-3`{xT)g?Y&Mc+x5g<u7Am~^Vp'
        '3$FBm1E*JTFh~@nKXMZl1;uR)gGQE=dEKlVCgJ}QXX8pH-ANl3n7vWgEPu>YX3il->R<_crEl3hs`;S_mW_Vr?BM8N|NJ1OG?SWI'
        '#umxBlvgSjtIqZrw>2ji2x9;I{NREWER0vc+!e18QjnN3=ZF6|@JjKrXg93AU|OxKDY3ioN;~~n<)yQ$6{$3Znc+j1{dGA~0WAj#'
        'l;+=B>#!e=y@MYW!<;If>)qNs>K*>CvUG5l>+=O|e95fP^X@aJO29J1fs{-%jH=dgxa*FW<`nCKRP#D5aDB(VWEEmH6GS8+WM<-E'
        'Tb1lYGtD%XXj54ehYhhz{k5;b<wR0>(N?0`!)n(6Iws+xN1<n6)1d@*hH68$h4G%I4PyA$?tFlMDrc`z6!ws7d%2CGSS8%mo^0@r'
        'K?=B)iI3X|!zh&5)cDzTIH---7+U$Mjf@^Q(6;Lq>NARtqkzpw1)TDvfcHAkWxyj(BTxGszV9$?^U~AljK$88reknW1dz`p1c)aq'
        'IE92eCt;&?f9o;s!K9yhq4J$OLLs*Uy^#B&<#Nc*gziiv-IvvTjOl$uAUfMb?~AsLfGxy0#sO{q)`T%>dt_d%k!4l@*J^B)WYY<f'
        '<E42e7woO9Ei3FW+(2g5g;-Y~ony6&dH3XcBBV@&WfX~gvJ>6qzsFjcg4#-T6{7DV(#3K<bq5}BTSczHt<V;Eh}$YmE)v9#>dtz_'
        '!SS%%chMSYO!bI2o40ii<f!%s{zW5=F~D|*oJS4=IFM1f@r&hX`6`pmUO1c%M@P#jw&UfW5)WmhkIQ~!5-*M{D$X%)M&$iF%W5)r'
        '(jN37Zsz@!(I_gAfI?u;YuCPR%0I&NvY?euu}y7>$Kr&thQu8OZPHd#NdOS$#yvIFGmax@ADJv^i6p6m3Tg*b*5;{5taMNsXz@7z'
        '$Kk1tO22`U%8g&e2xhqfgOe(~k*^}sNi;u1gT#^U9E)FpVmol0vw6LsTiJ=WZH9FV4ve8@JjwLoT=++dM$VmsrH=cg=DrU$^X=Fn'
        'R$R>xWBoAm-i=YzeXdw$w!&o?b;oBqdU`0Z!TPbXUv$?{i4vf_?Y%<^G^1xqN;?l6^T+YL)C9()<)G=Pj&0_&T$nJia>3;e+Y6d1'
        'i>7T}44i8upZV)NY+Ib8LtUj04C7;UVJ?-B<?*`;0;m|afiP@Fe(_kj&h*l5u`VdRezXwN<BI`~1Y5w~O+Ka4<n#=1@a!VJI6F9S'
        'LO^;fk5YI1@aqMI+EmADm&(DuJ-NXX)H{$)DOS?rPN|2<*5gLS*Ka^}?z8xoMYnf2gY>z_`<}KX;Si(vqv0FcHP+dEV*H^txHjns'
        'jEL=pE=q3fOA-kQXPbI-x?r?##kuk)_q+HYp9^27hnGe{hYN)PHAp1HAHNiJ6h@AY=UVDea!t~zOP&dQb0yI?@$!Xq;2fhmw?kyR'
        '?c|D@Q`}G9)@0z1F3xJsofH<Tb*<VUQ}>i(ov`jX2xiOA>innuJ+9SG3A?l9+(Ap9m{pP>R2~+}yG*_l(7T?J9yS=Xz)gvo`YvlJ'
        '`PH@KX0}RhX~3}EJO_oOI~%#*33#Lc44+aSgXWTrEDMyqEJHSm)aum|u#g$l(5W*PSqm=j+^&a$-Qk?_0fG<($seqUX>|+oE_%rJ'
        'tZ0_Xu^W3+g(3{Zgk{=J-ehzz1Of5M_LH4&$q*nbn|E+7JjK}U9E67^65SFW@9c=Hw@IhjW$NUHf%-}^>O9ewPOWHAdZ&wtk-7_l'
        'PWuQT{`GCL%Inb#n8zSK^$}-2vJH$JikX*hb95qhDQ3uG=mS~OjUZ*m4`M#C>rTalg4WtM>J{%Cgobwx1#;$5Zw8FUM8MsHFe(1Q'
        'LB~-S3S3xPh!h<H>kC}7jDR15V(V;AhF}CM+pocCG?XI{Blr?E$1OIuk|0V|$rv9GV>ibV1I@L+ihy`VUq-zr(SS%My-rNB61^>j'
        'c`PP9#dZm3_Qu2D`0hrIx4Ggr;P7v%<~i|el#I&>#qrN&1yo6u0(@mR`Cc3+{zhV3y-+9^I#)b*Ke~;?FW&VfzOt_`TNKx>V*?Jy'
        'yT$>jJmq1uE`z!0v4!4gh9RqZ8q3Hp!s`3x>m5Ne2y8Xdqxf$?XO8gO9(?>?v!dL$o?|}xxTYvO<A+SsqscadBEHTRKm<W+F7`aT'
        '$->(l9N{)-oVse`G7!c_o|$>dcHwHfJoGx2QIzsk#XJ)|j<Oh>7Ui4H9g82`?TT;K#yw~cNK|(^+y*wI24#mmP{CQto8oqf#8YG9'
        'i$<bRXb*S}Lcz){JK9H|QB{hgCYjq0S08CSPK@j(hgdi=4&h2ixe4#ahhSmdVW#M?eHlrS0_*_ydI5)w%ue`*Nb~?|QC;UZ74ZZ2'
        'Z0<Kwk#bG0NQrUpYFw4UnJh}-Cpv-*sSV?7GDAH>a<F3~X&MM_niWi(=}ze?*n(hWS<9)BT02Xlhik1jl~l{gawJ$mPE@G@rhX}='
        'l-N-XG3hkK4iI<aIp7Hn>u8CZ#=D}SKsc7kwl!2ORXF6UeHWlGkW=({7F3*!x*S&BoWow899dg?T+X)&_QWtO(rskq1hsO&=7+@h'
        '8kfSsg|+eGvac=-SsuYs^0tknNyE@;^#u2OYiR|Smvf3}d9!t&Vxslz1Ihd6GMhR#BorEHp4YSo;2j7q7#oU)HH;4FXt6?rgFKTp'
        'BKC|cA_C}66X*?xJk7BdCuybK)u{cYn<T2Gvl7W*vIQT`5uDD$Huhy-(IK&NPd#e36)|1S4y^aub^f>qet<V~)s&h)OG57Dt^FK4'
        'g4dhEeDDIT#^dF@$jOYKV{_3YkP5uQ(BCpi=p?X+HpEq&m0Xu5@^u-uk=$sJJCuY8Gt9IL2f4({w*6k|D;~6Q12>I<z3N;lD(`{S'
        '<7$nmtWsXxdn*}RGOw`G%qnhmbuErxyP4C_Myq7K$B+#0iLU`RiU4IeN3+Q3n2uhd&pX+unPU#W9G|etJjI0U=jdFh@YS|>qk@1F'
        'Q&vj@-P&U_G^9D0`z@ID2P4x7;nK87^OQCz6AC80PgxDPICDQvcAyPZ$N#N7(kf;^IKD~Ipc{TxJ9+0GycR!v+1qm3XRFRQSKF-W'
        '&~{eg=qQr6o=dIj;LlN)9k&*J1bhXBdXX}_yb2m!R2G6#eEW+<eNMLi=g+#2KjT(A4SESnm@CsqpBFO-{rNR}%HFDkxB*WjSBkyJ'
        'RUdJV27zm5yK%)PD%PmOk`6x#zvj3u7!!d18T$N5?)FyD-8CdUOqS6-9M{uZJ5njFwVG`-c-`&Sx8%TgN?$M`-|e0;O;ols!#SGV'
        'OmYqdjNwmH^sY=(rRYQU8WSwD%)h)Qe@SeSLSw=UerkHq#$qp)R4_*OnbGw$l%w)o7^en5#C=L1yeE&Wjz@bl&Wnh)N*sKOPYjcX'
        '*t6;2>eN9dqwd9n0bgJYRMt%N&zE34dxqGqt}96bHm_%l2sf|8uLqN&4p&oIYyx;xpDaxBc!3!+Rs<Y}NW3;^$Muz05GTAD=M)jz'
        '?XCr<#`;Pq*G|Kl;$k(sxE}~y>nbd>#im7-iK;ifj5_NU+=@;DOngrQKAiuwpRDU)k=;5CDwAh;|5V#mzWq*?$@l2#fp<p%hplxz'
        'Q4&au2thHl_hM8-<mm$DLj%B>ko?p~AcC5_NRIIe*}w!=8NK&fbv%JMc{oVJ%mnuBq<bu<+dVvRVM+4c09^7#Lnf)~?#YUQ@cu<('
        '+uA8NuG<WiAt6d4Eq)k;fsH+bv%h;|ch_T&Sh@D+!Eu67oW0vS*G+9e1#yWTqm>YgbI+8!VZUgeqm5zTai;6)dgE1vQp&6Ie)Rlo'
        '|Hlg=BQFon&kv7Z@G8@kd0wST9Bm&0DvZfM<(l#4k+&o)prXVk(5+7LDVw^jo*p=PD=K0py8#9<erQE?ybg-VgX>aM?m=F@)#xDW'
        'OUaTVvA4)G|7}(mba{a;dK@+V8ikLVBYUfv=A^Bm?ZaON5)M*zr;i(Nd$U)ZN^AEN(_PZOw8ErhwcF`K_fbc!jSljz${jTS0WC01'
        'e?Wy9F44hVHOn7X4?_>FpcY-&;UvTluXsDZCEkxhlkLU$xTdEMJaY6FW!9v7B}xgKn_(9&laA4>Mfk|J)@-A#SEk$BgQ-EdmX!zm'
        'LG?OutS!9T*ifJRjoFb3kWEQBmUlZzqt{)^EWA#c7-Zskeq%eKrBx?wF`-n#P;Z*#KwxOeTPV68;IB$l`N-Xo(_7#~)a2enzga4H'
        '7NXky9W=X5G*g8^)DCD8CUJFNGFbzT)wi}kDIQ4>N9l8Q7f#r@)y<ot5s0vG0=gaO_)YC)Bw=j^s)(MT&ZJK!+Lf;3_9ClCJ#Mgi'
        'bdVjp32(LE@^19GOPxT*3{uM4O}B9?g(+KtZkEVM7GW5fiI-W*M7jZ{5Do`CYAJ1u@wV*f3R`QVH=8z<C96_X-JSL@I!t2N;~=Nx'
        '=Ckm*CE9PHU>H##tVo#Y@Hox@feY88ZglM~ZYNxK?Rgxowo<|lSNScg2jqDatfQkibYPwL@IcsSjrE7R^2By;ZH|#%cc0-M)ixPf'
        'uo8Ky_bHRY9&EUk<<*J#pi1IGxj9t6f&WA5u3btU^;WK>wb)=cwTINza>1&)Xd=6U1l(X{i$h`)TDIHwP4(HhM>9k>fmym~R&4r?'
        'Dw`K3d+mW3G;AVOYCCO}nuKF~vw@`*;~bcqPPtO6dp$x~ZDhaWckp2gwY&PtHP>Bjqosq@@s4gKQq)DBMas&XqMmtp$=r***zMuN'
        'u@bDk7Ae7655h^Swz^Lm@CxYI$n|YwRNnLtHbU-#Z@CDj%9^5!5Jnyl#rB9UPx%2|%axjz->XOEq$scHcR$i<Eq*Ic0jK?z{zUbv'
        'BQDcxY|)kUrD1+4ad!jl`%;-(P2L9T1-X?`XDGLR6h3K3onE!8`egDr3b;NY9z4o03R(SyFwx#TqmJuw4w3*uuf(DViE~O~N=7Tf'
        '{rg}98G6M%OG_OgW_7?UzJ`=Ln!ZkL#a@SsahJ4K398$894#AUetqdR$2W!4NOHwy0#5WtDX3f))V}d@DZ(zpqaa;2Fp{3R)$52#'
        'uai%5W0!VM3W%*O070dSqI8QXMgC4xiLQ>l<i8h-9^I`a$=3e`%7m_T'
    ),
}
PINNED_TEMP = tempfile.TemporaryDirectory(prefix="module-pinned-fixture-")
SCRIPTS = Path(PINNED_TEMP.name)
for _name, _encoded in PINNED_DATA.items():
    _bytes = zlib.decompress(base64.b85decode(_encoded))
    if hashlib.sha256(_bytes).hexdigest() != PINNED_SHA256[_name]:
        raise RuntimeError("pinned fixture digest mismatch")
    (SCRIPTS / _name).write_bytes(_bytes)
ADAPTER = SCRIPTS / "module-release.sh"
HELPER = SCRIPTS / "release_artifacts.py"
WORKFLOW = Path(__file__).resolve().parents[2] / "workflows" / "publish.yml"
REAL_GIT = shutil.which("git")
BASH = shutil.which("bash")
assert REAL_GIT is not None, "git is required for the hermetic source fixture"
assert BASH is not None, "bash is required to execute the actual workflow shell"
MAINTAINER = "wisdommen"
REPO = "UltiKits/UltiBackup"
IDENTIFY = "ultibackup"
PREFIX = "UltiBackup"
VERSION = "2.0.0"
PRIOR = "1.0.0"
DRAFT_ID = "777001"
INSTALLATION_ID = "inst-7f3c9a1e2b4d4c55"
INTEGRITY = "sha512-SMZXRvp9MBSX1qpPDzC+UbvsYv9pGVZWWt/Eqjq02rs9xfg5dopzYZuKpRuGmCOww+8rF3suGsmp4SKSUqtgqA=="
REGISTRY_URL = "https://registry.npmjs.org/@ultikits%2fcli"
CDN = "https://cdn.fixture.invalid/"
INPUT_KEYS = ("artifact_sha256", "draft_release_id", "installation_id", "manifest_sha256",
              "package_metadata_sha256", "source_head", "version")

# One shared fixture. The private release-evidence checker's suite asserts the same two literals for the
# same objects, so the producer of the dispatch record and this runner-side helper prove one canonical
# serialization without either importing the other.
SHARED_INPUTS = {"source_head": "0123456789abcdef0123456789abcdef01234567", "version": "3.0.0",
                 "draft_release_id": "123456789", "manifest_sha256": "a" * 64, "artifact_sha256": "b" * 64,
                 "package_metadata_sha256": "c" * 64, "installation_id": "inst-shared-fixture-01"}
SHARED_INPUTS_SHA256 = "10fd2bfe8fa2d4d3fb8f41fd8cd6d03b1c65846e64b49771a7657c917227b8b6"
SHARED_METADATA_SHA256 = "41d7ae9045c1550039aedfed19f4093f0b1f3a49628723b2fa6bf8f88723943d"


def metadata_doc():
    """Exact-version registry metadata for @ultikits/cli 1.2.0 (sanitized fixture)."""
    return {"name": "@ultikits/cli", "version": "1.2.0", "dist": {"integrity": INTEGRITY},
            "_npmUser": {"name": "wisdomme"}, "scripts": {"build": "tsc"},
            "dependencies": {"commander": "^12.0.0"}}


# The reference wrapper: the step every module's existing publish.yml adds. The workflow never expands
# dispatch inputs in a script; the helper reads them from GITHUB_EVENT_PATH as data. The helper digest is a
# reviewed literal (substituted per test because the fixture copies the helper).
WRAPPER_YML = WORKFLOW.read_text(encoding="utf-8")


def wrapper_script(yml):
    """Return the run block of the 'Publish retained draft' step, dedented, exactly as Actions runs it."""
    lines = yml.splitlines()
    start = lines.index("      - name: Publish retained draft")
    run = next(i for i in range(start, len(lines)) if lines[i].strip() == "run: |")
    indent = len(lines[run]) - len(lines[run].lstrip())
    body = []
    for line in lines[run + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indent:
            break
        body.append(line)
    return textwrap.dedent("\n".join(body)) + "\n"


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def load_helper():
    spec = importlib.util.spec_from_file_location("release_artifacts_under_test", str(HELPER))
    assert spec is not None and spec.loader is not None, "the pinned helper needs a Python loader"
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def plugin_yml(version=VERSION, api="630", identify=IDENTIFY, main="com.example.fixture.UltiBackup"):
    lines = ["name: UltiBackup", "version: '%s'" % version, "main: %s" % main, "api-version: %s" % api]
    if identify is not None:
        lines.append("identify-string: %s" % identify)
    return ("\n".join(lines) + "\n").encode("utf-8")


def jar_bytes(plugin, main="com.example.fixture.UltiBackup", stamp=b""):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(zipfile.ZipInfo("plugin.yml", (2026, 10, 19, 0, 0, 0)), plugin)
        zf.writestr(zipfile.ZipInfo(main.replace(".", "/") + ".class", (2026, 10, 19, 0, 0, 0)),
                    b"\xca\xfe\xba\xbe fixture class bytes" + stamp)
    return buffer.getvalue()


POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.ultikits.plugins</groupId>
    <artifactId>UltiBackup</artifactId>
    <version>{version}</version>
</project>
"""

# ------------------------------------------------------------------------------------------ fakes

FAKE_GH = r'''#!{python} -I
import json, os, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "gh", "argv": args}) + "\n")
path = os.path.join(STATE, "github.json")
gh = json.load(open(path, encoding="utf-8"))
def save():
    json.dump(gh, open(path, "w", encoding="utf-8"), indent=2, sort_keys=True)
def http(code):
    sys.stderr.write("gh: HTTP %d\n" % code)
    sys.exit(1)
if not args or args[0] != "api":
    sys.exit(96)
method, headers, fields, endpoint, i = "GET", [], {}, None, 1
while i < len(args):
    a = args[i]
    if a in ("-X", "--method"):
        method = args[i + 1]; i += 2
    elif a in ("-H", "--header"):
        headers.append(args[i + 1]); i += 2
    elif a in ("-F", "--field"):
        k, v = args[i + 1].split("=", 1); fields[k] = v; i += 2
    elif a.startswith("-") or endpoint is not None:
        sys.exit(96)
    else:
        endpoint = a; i += 1
prefix = "repos/%s/" % gh["repo"]
if endpoint is None or not endpoint.startswith(prefix):
    http(404)
parts = endpoint[len(prefix):].split("/")
fail = gh.get("fail", {})
counts = gh.setdefault("counts", {})
def count(name):
    counts[name] = counts.get(name, 0) + 1
    save()
    return counts[name]
def release_view(rel):
    return {"id": int(rel_id), "draft": rel["draft"], "target_commitish": rel["target_commitish"],
            "tag_name": rel["tag_name"],
            "assets": [{"id": a["id"], "name": a["name"], "size": os.path.getsize(os.path.join(STATE, a["file"])),
                        "state": "uploaded"} for a in rel["assets"]]}
if method == "GET" and fail.get("get"):
    http(int(fail["get"]))
if method == "GET" and len(parts) == 2 and parts[0] == "releases" and parts[1].isdigit():
    rel_id = parts[1]
    n = count("release_reads")
    hook = gh.get("on_release_read", {}).get(str(n))
    if hook:
        gh["releases"][rel_id].update(hook)
        save()
    rel = gh["releases"].get(rel_id)
    if rel is None:
        http(404)
    print(json.dumps(release_view(rel)))
elif method == "GET" and len(parts) == 3 and parts[:2] == ["releases", "assets"]:
    if "Accept: application/octet-stream" not in headers:
        sys.exit(96)
    for rel in gh["releases"].values():
        for a in rel["assets"]:
            if str(a["id"]) == parts[2]:
                sys.stdout.buffer.write(open(os.path.join(STATE, a["file"]), "rb").read())
                sys.exit(0)
    http(404)
elif method == "GET" and parts[:3] == ["git", "matching-refs", "tags"] and len(parts) == 4:
    print(json.dumps([{"ref": "refs/tags/" + t, "object": {"sha": s, "type": "commit"}}
                      for t, s in sorted(gh["tags"].items()) if t.startswith(parts[3])]))
elif method == "GET" and parts[:3] == ["git", "ref", "heads"] and len(parts) >= 4:
    branch = "/".join(parts[3:])
    if branch != gh["default_branch"]:
        http(404)
    n = count("head_reads")
    seq = gh.get("head_sequence") or []
    sha = seq[min(n, len(seq)) - 1] if seq else gh["default_head"]
    print(json.dumps({"ref": "refs/heads/" + branch, "object": {"sha": sha, "type": "commit"}}))
elif method == "PATCH" and len(parts) == 2 and parts[0] == "releases" and fields == {"draft": "false"}:
    rel_id = parts[1]
    rel = gh["releases"].get(rel_id)
    if rel is None:
        http(404)
    if fail.get("undraft"):
        http(int(fail["undraft"]))
    rel["draft"] = False
    gh["tags"].setdefault(rel["tag_name"], rel["target_commitish"])
    save()
    print(json.dumps(release_view(rel)))
else:
    sys.stderr.write("fake gh: unmodelled call\n")
    sys.exit(96)
'''

FAKE_CURL = r'''#!{python} -I
import json, os, pathlib, sys
state = pathlib.Path(os.environ["FAKE_STATE_DIR"])
args = sys.argv[1:]
with (state / "calls.jsonl").open("a") as f:
    f.write(json.dumps({"tool": "curl", "argv": args}) + "\n")
if len(args) != 5 or args[:2] != ["--fail", "--silent"]:
    sys.exit(96)
url, out = args[2], args[4]
if args[3] != "--output":
    sys.exit(96)
prefix = "https://raw.githubusercontent.com/UltiKits/UltiTools-Reborn/f061477e9049f75ab297266d70604136998f01c6/.github/scripts/"
if not url.startswith(prefix) or url[len(prefix):] not in ("module-release.sh", "release_artifacts.py"):
    sys.exit(96)
name = url[len(prefix):]
data = (pathlib.Path(os.environ["PINNED_FIXTURE_DIR"]) / name).read_bytes()
flags = json.loads((state / "flags.json").read_text())
if flags.get("corrupt_download") == name:
    data += b"\n# corrupted download\n"
pathlib.Path(out).write_bytes(data)
'''


FAKE_NPM = r'''#!{python} -I
import json, os, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "npm", "argv": args}) + "\n")
if args != ["install", "--global", "--ignore-scripts", "--no-audit", "--no-fund", "@ultikits/cli@1.2.0"]:
    sys.stderr.write("fake npm: only the pinned global install is modelled\n")
    sys.exit(96)
flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
if flags.get("npm_fail"):
    sys.exit(1)
marker = os.path.join(STATE, "cli-installed")
count = int(open(marker).read()) if os.path.exists(marker) else 0
open(marker, "w").write(str(count + 1))
side = flags.get("install_side_effect_catalogue")
if side:
    # Another writer occupies the version while this run installs (concurrency control).
    cpath = os.path.join(STATE, "catalogue.json")
    cat = json.load(open(cpath, encoding="utf-8"))
    open(os.path.join(STATE, "catalogue-files", side["file"]), "wb").write(side["bytes"].encode("latin-1"))
    cat["versions"].append({"version": side["version"], "level": 630, "file": side["file"],
                            "identify": cat["module"]["identify"]})
    json.dump(cat, open(cpath, "w", encoding="utf-8"), indent=2, sort_keys=True)
'''

FAKE_ULTIKITS = r'''#!{python} -I
import json, os, shutil, sys
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "ultikits", "argv": args, "cwd": os.getcwd(),
                         "token_present": bool(os.environ.get("ULTIKITS_TOKEN"))}) + "\n")
if not os.path.exists(os.path.join(STATE, "cli-installed")):
    sys.stderr.write("ultikits: command not found (the CLI was never installed)\n")
    sys.exit(127)
flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
if args == ["--version"]:
    print(flags.get("cli_version", "1.2.0"))
    sys.exit(0)
if args == ["publish", "--help"]:
    print(flags.get("cli_help", "Usage: ultikits publish [options] [jar-path]\n\nOptions:\n"
          "  --id <identifyString>       Module identify string\n"
          "  --name <name>               Module display name\n"
          "  --module-version <version>  Module version\n"
          "  --changelog <text>          Version changelog\n"
          "  -y, --yes                   Skip confirmation prompts\n"))
    sys.exit(0)
if len(args) < 6 or args[0] != "publish":
    sys.exit(96)
jar, rest = args[1], args[2:]
opts = {}
i = 0
while i < len(rest):
    if rest[i] == "--yes":
        opts["yes"] = True; i += 1
    elif rest[i] in ("--id", "--module-version", "--changelog"):
        opts[rest[i]] = rest[i + 1]; i += 2
    else:
        sys.exit(96)
if not os.environ.get("ULTIKITS_TOKEN") or "--id" not in opts or "--module-version" not in opts:
    sys.exit(1)
if flags.get("upload_fail"):
    sys.stderr.write("Upload failed: HTTP 502\n")
    sys.exit(1)
cpath = os.path.join(STATE, "catalogue.json")
cat = json.load(open(cpath, encoding="utf-8"))
name = "%s-%s.jar" % (opts["--id"], opts["--module-version"])
data = open(jar, "rb").read()
if flags.get("upload_corrupt"):
    data = data + b"corrupted in transit"
open(os.path.join(STATE, "catalogue-files", name), "wb").write(data)
cat["versions"].append({"version": opts["--module-version"], "level": int(flags.get("upload_level", 630)),
                        "file": name, "identify": opts["--id"]})
if flags.get("upload_shadows_no_level"):
    cat["versions"].append({"version": "1.9.9", "level": 620, "file": name, "identify": opts["--id"]})
json.dump(cat, open(cpath, "w", encoding="utf-8"), indent=2, sort_keys=True)
print("Published %s@%s" % (opts["--id"], opts["--module-version"]))
'''

FAKE_MVN = r'''#!{python} -I
import json, os, sys
with open(os.path.join(os.environ["FAKE_STATE_DIR"], "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "mvn", "argv": sys.argv[1:]}) + "\n")
sys.stderr.write("fake mvn: publication never builds\n")
sys.exit(97)
'''

FAKE_GIT = r'''#!{python} -I
import json, os, sys
args = sys.argv[1:]
with open(os.path.join(os.environ["FAKE_STATE_DIR"], "calls.jsonl"), "a", encoding="utf-8") as fh:
    fh.write(json.dumps({"tool": "git", "argv": args}) + "\n")
rest = args[2:] if args[:1] == ["-C"] else args
if not rest or rest[0] not in ("rev-parse", "cat-file", "merge-base"):
    sys.stderr.write("guarded git: only read-only subcommands are allowed\n")
    sys.exit(95)
os.execv({git!r}, [{git!r}] + args)
'''

FAKE_PYTHON = r'''#!/bin/sh
exec {python} -I {shim} "$@"
'''

PYTHON_SHIM = r'''
import email.message, io, json, os, runpy, sys, urllib.error, urllib.parse, urllib.request
STATE = os.environ["FAKE_STATE_DIR"]
args = sys.argv[1:]
if args[:1] == ["-I"]:
    args = args[1:]
if not args or args[0].startswith("-"):
    sys.exit(94)

def log(entry):
    with open(os.path.join(STATE, "calls.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(entry) + "\n")

def vt(v):
    return tuple(int(p) for p in v.split("."))

def envelope(status, data=None, msg="Success"):
    body = {"code": str(status), "msg": msg}
    if data is not None:
        body["data"] = data
    return status, json.dumps(body).encode("utf-8")

def route(url):
    parts = urllib.parse.urlsplit(url)
    flags = json.load(open(os.path.join(STATE, "flags.json"), encoding="utf-8"))
    if url == "https://registry.npmjs.org/@ultikits%2fcli":
        if flags.get("registry_status"):
            return int(flags["registry_status"]), b"{}"
        return 200, open(os.path.join(STATE, "npm-packument.json"), "rb").read()
    if parts.scheme == "https" and parts.netloc == "cdn.fixture.invalid":
        path = os.path.join(STATE, "catalogue-files", parts.path.lstrip("/"))
        return (200, open(path, "rb").read()) if os.path.exists(path) else (404, b"")
    if parts.scheme != "https" or parts.netloc != "api.ultikits.com":
        raise urllib.error.URLError("fake internet: no route to %s" % parts.netloc)
    cat = json.load(open(os.path.join(STATE, "catalogue.json"), encoding="utf-8"))
    if flags.get("catalogue_status"):
        return envelope(int(flags["catalogue_status"]), msg="fake outage")
    query = urllib.parse.parse_qs(parts.query)
    seg = parts.path.strip("/").split("/")
    module = cat.get("module")
    if seg == ["plugin", "get"]:
        if module and query.get("identifyString") == [module["identify"]]:
            return envelope(200, {"id": module["id"], "identifyString": module["identify"]})
        return envelope(404, msg="Plugin not found")
    if not module or len(seg) < 3 or seg[0] != "plugin" or seg[1] != str(module["id"]):
        return envelope(404, msg="Plugin not found")
    versions = cat["versions"]
    level630 = query.get("apiLevel") == ["630"] and query.get("minApiLevel") == ["630"]
    def link(v):
        return "https://cdn.fixture.invalid/" + v["file"]
    if seg[2:] == ["versions-detail"]:
        return envelope(200, [{"version": v["version"], "downloadLink": link(v)} for v in versions])
    if seg[2:] == ["latest"]:
        pool = [v for v in versions if (v["level"] == 630) == level630]
        if not pool:
            return envelope(404, msg="No versions found for this plugin")
        return envelope(200, max(pool, key=lambda v: vt(v["version"]))["version"])
    if len(seg) == 4 and seg[3] == "download":
        match = [v for v in versions if v["version"] == seg[2] and (v["level"] == 630) == level630]
        return envelope(200, link(match[0])) if match else envelope(404, msg="Version not found")
    return envelope(404, msg="Not found")

class Response(io.BytesIO):
    def __init__(self, url, status, body):
        io.BytesIO.__init__(self, body)
        self.status, self.url, self.headers = status, url, email.message.Message()
    def geturl(self):
        return self.url
    def getcode(self):
        return self.status
    def __enter__(self):
        return self
    def __exit__(self, *exc):
        self.close()

def fake_urlopen(request, timeout=None, **kwargs):
    url = request.full_url if hasattr(request, "full_url") else request
    log({"tool": "https", "url": url})
    status, body = route(url)
    if status >= 400:
        raise urllib.error.HTTPError(url, status, "fake", email.message.Message(), io.BytesIO(body))
    return Response(url, status, body)

urllib.request.urlopen = fake_urlopen
sys.argv = [args[0]] + args[1:]
runpy.run_path(args[0], run_name="__main__")
'''


class PublishFixture(object):
    """One module repository at its accepted head, a retained candidate staged on a draft release, and the
    fake GitHub, npm registry and catalogue it publishes to."""

    def __init__(self, root, identify=IDENTIFY, version=VERSION):
        self.root = Path(root)
        self.identify, self.version = identify, version
        for name in ("bin", "state", "checkout", "retained", "runner", "home", "tools"):
            (self.root / name).mkdir(parents=True)
        self.bin, self.state, self.checkout = self.root / "bin", self.root / "state", self.root / "checkout"
        self.retained, self.runner, self.tools = self.root / "retained", self.runner_dir(), self.root / "tools"
        (self.state / "assets").mkdir()
        (self.state / "catalogue-files").mkdir()
        self.install_fakes()
        self.make_checkout()
        self.make_candidate()
        self.stage_github()
        self.stage_catalogue()
        self.write_json(self.state / "npm-packument.json",
                        {"name": "@ultikits/cli", "dist-tags": {"latest": "1.2.0"},
                         "versions": {"1.2.0": metadata_doc()}})
        self.flags = {}
        self.save_flags()
        # The wrapper fetches the adapter and helper at a reviewed framework commit; the fixture copies the
        # files under test. A missing file is copied as missing so the run itself fails, not the fixture.
        for source in (ADAPTER, HELPER):
            if source.exists():
                shutil.copy2(str(source), str(self.tools / source.name))
        self.wrapper_yml = WRAPPER_YML.replace("@HELPER_SHA256@", self.helper_sha256())
        self.inputs = self.valid_inputs()
        self.write_event(self.inputs)

    def runner_dir(self):
        return self.root / "runner"

    @staticmethod
    def write_json(path, obj):
        Path(path).write_text(json.dumps(obj, indent=2, sort_keys=True), encoding="utf-8")

    def helper_sha256(self):
        return sha256_bytes((self.tools / "release_artifacts.py").read_bytes()) \
            if (self.tools / "release_artifacts.py").exists() else sha256_bytes(HELPER.read_bytes())

    def install_fakes(self):
        shim = self.root / "python_shim.py"
        shim.write_text(PYTHON_SHIM, encoding="utf-8")
        scripts = {"curl": FAKE_CURL, "gh": FAKE_GH, "npm": FAKE_NPM, "ultikits": FAKE_ULTIKITS, "mvn": FAKE_MVN,
                   "git": FAKE_GIT.replace("{git!r}", repr(REAL_GIT)),
                   "python3": FAKE_PYTHON.replace("{shim}", str(shim))}
        for name, text in scripts.items():
            path = self.bin / name
            path.write_text(text.replace("{python}", sys.executable), encoding="utf-8")
            path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def git(self, *args):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.root / "home"),
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "f@example.invalid",
               "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "f@example.invalid"}
        proc = subprocess.run([REAL_GIT, "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                               "-c", "init.defaultBranch=master"] + list(args),
                              cwd=str(self.checkout), env=env, capture_output=True, text=True, timeout=60)
        if proc.returncode != 0:
            raise RuntimeError("fixture git failed: %s %s" % (args, proc.stderr))
        return proc.stdout.strip()

    def make_checkout(self):
        self.git("init", "-q")
        (self.checkout / "pom.xml").write_text(POM_TEMPLATE.format(version=self.version), encoding="utf-8")
        (self.checkout / "ultikits.json").write_text(json.dumps({"identifyString": self.identify,
                                                                 "name": "UltiBackup"}), encoding="utf-8")
        self.git("add", "pom.xml", "ultikits.json")
        self.git("commit", "-q", "-m", "accepted source")
        self.git("remote", "add", "origin", "https://github.com/%s.git" % REPO)
        self.head = self.git("rev-parse", "HEAD")
        self.tree = self.git("rev-parse", "HEAD^{tree}")

    @property
    def jar_name(self):
        return "%s-%s.jar" % (PREFIX, self.version)

    @property
    def manifest_name(self):
        return "%s-%s-release-manifest.json" % (PREFIX, self.version)

    def make_candidate(self):
        self.jar = jar_bytes(plugin_yml(version=self.version, identify=self.identify))
        (self.retained / self.jar_name).write_bytes(self.jar)
        self.manifest = {
            "schema": "phase21-candidate/1", "evidence_state": "observed", "repo": REPO, "kind": "module",
            "label": "final-candidate", "formal_acceptance": False, "release_authorized": False,
            "source_head": self.head, "product_tree": self.tree, "version": self.version,
            "identify_string": self.identify, "prior_served_version": PRIOR, "plugin_api_version": "630",
            "artifacts": [{"filename": self.jar_name, "bytes": len(self.jar), "sha256": sha256_bytes(self.jar),
                           "role": "primary"}],
        }
        self.manifest_bytes = json.dumps(self.manifest, indent=2, sort_keys=True).encode("utf-8")
        (self.retained / self.manifest_name).write_bytes(self.manifest_bytes)

    def stage_github(self):
        (self.state / "assets" / "11").write_bytes(self.jar)
        (self.state / "assets" / "12").write_bytes(self.manifest_bytes)
        self.write_json(self.state / "github.json", {
            "repo": REPO, "default_branch": "master", "default_head": self.head,
            "tags": {"v" + PRIOR: "f" * 40},
            "releases": {DRAFT_ID: {"draft": True, "target_commitish": self.head, "tag_name": "v" + self.version,
                                    "assets": [{"id": 11, "name": self.jar_name, "file": "assets/11"},
                                               {"id": 12, "name": self.manifest_name, "file": "assets/12"}]}},
        })

    def stage_catalogue(self):
        self.prior_jar = jar_bytes(plugin_yml(version=PRIOR, api="620"))
        (self.state / "catalogue-files" / ("%s-%s.jar" % (self.identify, PRIOR))).write_bytes(self.prior_jar)
        self.write_json(self.state / "catalogue.json", {
            "module": {"id": 17, "identify": self.identify},
            "versions": [{"version": PRIOR, "level": 620, "file": "%s-%s.jar" % (self.identify, PRIOR),
                          "identify": self.identify}]})

    def save_flags(self):
        self.write_json(self.state / "flags.json", self.flags)

    def github(self):
        return json.loads((self.state / "github.json").read_text(encoding="utf-8"))

    def update_github(self, mutate):
        doc = self.github()
        mutate(doc)
        self.write_json(self.state / "github.json", doc)

    def catalogue(self):
        return json.loads((self.state / "catalogue.json").read_text(encoding="utf-8"))

    def update_catalogue(self, mutate):
        doc = self.catalogue()
        mutate(doc)
        self.write_json(self.state / "catalogue.json", doc)

    def valid_inputs(self):
        return {"source_head": self.head, "version": self.version, "draft_release_id": DRAFT_ID,
                "manifest_sha256": sha256_bytes(self.manifest_bytes), "artifact_sha256": sha256_bytes(self.jar),
                "package_metadata_sha256": SHARED_METADATA_SHA256, "installation_id": INSTALLATION_ID}

    def write_event(self, inputs, ref="refs/heads/master", raw=None):
        path = self.runner / "event.json"
        if raw is not None:
            path.write_bytes(raw)
        else:
            path.write_text(json.dumps({"inputs": inputs, "ref": ref, "repository": {"full_name": REPO},
                                        "sender": {"login": MAINTAINER},
                                        "workflow": ".github/workflows/publish.yml"}), encoding="utf-8")
        return path

    def env(self, **overrides):
        env = {
            "PATH": "%s:/usr/bin:/bin" % self.bin, "HOME": str(self.root / "home"), "LANG": "C.UTF-8",
            "FAKE_STATE_DIR": str(self.state),
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(self.runner / "event.json"),
            "GITHUB_REF": "refs/heads/master", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_RUN_ID": "9001",
            "GITHUB_REPOSITORY": REPO, "GITHUB_TRIGGERING_ACTOR": MAINTAINER, "GITHUB_ACTOR": MAINTAINER,
            "GITHUB_SHA": self.head, "GITHUB_WORKSPACE": str(self.checkout), "RUNNER_TEMP": str(self.runner),
            "GITHUB_STEP_SUMMARY": str(self.runner / "summary.md"),
            "GH_TOKEN": "fixture-gh-token-0000", "ULTIKITS_TOKEN": "fixture-catalogue-token-0000",
            "RELEASE_TOOLS": str(self.tools), "PINNED_FIXTURE_DIR": str(SCRIPTS),
        }
        for key, value in overrides.items():
            if value is None:
                env.pop(key, None)
            else:
                env[key] = value
        return env

    def run_wrapper(self, **env):
        proc = subprocess.run([BASH, "-c", wrapper_script(self.wrapper_yml)], cwd=str(self.checkout),
                              env=self.env(**env), capture_output=True, text=True, timeout=180)
        return proc.returncode, proc.stdout, proc.stderr

    def run_adapter(self, *extra, args=None, **env):
        argv = args if args is not None else [
            "--repo", REPO, "--identify-string", self.identify, "--default-branch", "master",
            "--maintainer", MAINTAINER, "--jar-prefix", PREFIX, "--helper-sha256", self.helper_sha256()]
        proc = subprocess.run([BASH, str(self.tools / "module-release.sh")] + argv + list(extra),
                              cwd=str(self.checkout), env=self.env(**env), capture_output=True, text=True,
                              timeout=180)
        return proc.returncode, proc.stdout, proc.stderr

    def calls(self, tool=None):
        path = self.state / "calls.jsonl"
        entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] \
            if path.exists() else []
        return [e for e in entries if tool is None or e["tool"] == tool]

    def install_calls(self):
        return self.calls("npm")

    def publish_calls(self):
        return [c for c in self.calls("ultikits") if c["argv"][:1] == ["publish"] and "--help" not in c["argv"]]

    def github_writes(self):
        return [c for c in self.calls("gh") if any(m in c["argv"] for m in ("PATCH", "POST", "DELETE", "PUT"))]

    def receipt(self):
        return json.loads((self.runner / "module-release-receipt.json").read_text(encoding="utf-8"))


class FixtureBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="p21-module-release-")
        self.f = PublishFixture(Path(self.tmp) / "fx")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def assert_nothing_installed_or_written(self, outcome=None):
        detail = "" if outcome is None else "\nstdout:\n%s\nstderr:\n%s" % outcome[1:]
        self.assertEqual(self.f.install_calls(), [], "no CLI installation may happen" + detail)
        self.assertEqual(self.f.calls("ultikits"), [], "the CLI may not run" + detail)
        self.assertEqual(self.f.github_writes(), [], "no GitHub write may happen" + detail)
        self.assertEqual(self.f.calls("mvn"), [], "publication never builds")
        self.assertTrue(self.f.github()["releases"][DRAFT_ID]["draft"], "the draft stays unpublished")
        self.assertFalse((self.f.state / "cli-installed").exists(), "the CLI was never installed")

    def assert_refused(self, outcome, code=None):
        status, out, err = outcome
        self.assertNotEqual(status, 0, msg=out + err)
        if code is not None:
            self.assertIn(code, err, msg=out + err)
        self.assert_nothing_installed_or_written(outcome)


class SharedDigestTests(unittest.TestCase):
    """The runner-side helper serializes exactly like the private producer of the dispatch record."""

    def test_inputs_digest_matches_the_shared_fixture(self):
        self.assertEqual(load_helper().dispatch_inputs_sha256(dict(SHARED_INPUTS)), SHARED_INPUTS_SHA256)

    def test_metadata_digest_matches_the_shared_fixture(self):
        self.assertEqual(load_helper().package_metadata_sha256(metadata_doc()), SHARED_METADATA_SHA256)

    def test_digest_is_independent_of_key_order(self):
        helper = load_helper()
        reordered = dict(reversed(list(SHARED_INPUTS.items())))
        self.assertEqual(helper.dispatch_inputs_sha256(reordered), SHARED_INPUTS_SHA256)


class TracerPublishTests(FixtureBase):
    """Valid dispatch -> wrapper -> adapter -> helper -> fresh metadata -> exact retained JAR -> readbacks."""

    def test_fakes_shadow_every_real_tool(self):
        path = self.f.env()["PATH"]
        for tool in ("gh", "npm", "ultikits", "mvn", "git", "python3"):
            self.assertEqual(shutil.which(tool, path=path), str(self.f.bin / tool))

    def test_valid_dispatch_publishes_the_exact_retained_jar_to_both_sides(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        installs = self.f.install_calls()
        self.assertEqual(len(installs), 1, "exactly one pinned installation")
        self.assertEqual(installs[0]["argv"][-1], "@ultikits/cli@1.2.0")
        publishes = self.f.publish_calls()
        self.assertEqual(len(publishes), 1)
        argv = publishes[0]["argv"]
        jar_path = argv[1]
        self.assertEqual(Path(jar_path).name, self.f.jar_name, "positional exact basename, no wildcard")
        self.assertEqual(argv[2:], ["--id", IDENTIFY, "--module-version", VERSION, "--yes"])
        self.assertTrue(publishes[0]["token_present"])
        served = self.f.catalogue()["versions"]
        self.assertEqual([v["version"] for v in served], [PRIOR, VERSION])
        self.assertEqual((self.f.state / "catalogue-files" / ("%s-%s.jar" % (IDENTIFY, VERSION))).read_bytes(),
                         self.f.jar, "the catalogue serves the accepted bytes")
        release = self.f.github()["releases"][DRAFT_ID]
        self.assertFalse(release["draft"])
        self.assertEqual(self.f.github()["tags"]["v" + VERSION], self.f.head)
        self.assertEqual(len(self.f.github_writes()), 1, "one undraft write; assets are never re-uploaded")
        self.assertEqual(self.f.calls("mvn"), [])

    def test_runner_prints_and_records_the_canonical_inputs_digest_and_run_identity(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        digest = sha256_bytes(canonical(self.f.inputs).encode("ascii"))
        self.assertIn("INPUTS_SHA256=%s" % digest, out)
        receipt = self.f.receipt()
        self.assertEqual(receipt["inputs_sha256"], digest)
        self.assertEqual(receipt["run_id"], "9001")
        self.assertEqual(receipt["run_attempt"], "1")
        self.assertEqual(receipt["event"], "workflow_dispatch")
        self.assertEqual(receipt["triggering_actor"], MAINTAINER)
        self.assertEqual(receipt["installation_id"], INSTALLATION_ID)
        self.assertEqual(receipt["installed_package"], "@ultikits/cli@1.2.0")
        self.assertEqual(receipt["package_metadata_sha256"], SHARED_METADATA_SHA256)
        self.assertEqual(receipt["result"], "complete")
        self.assertEqual(receipt["release_outcome"], "published")
        self.assertEqual(receipt["catalogue_outcome"], "uploaded")
        self.assertEqual(receipt["level630_after"], VERSION)
        self.assertEqual(receipt["no_level_before"], PRIOR)
        self.assertEqual(receipt["no_level_after"], PRIOR)
        summary = (self.f.runner / "summary.md").read_text(encoding="utf-8")
        self.assertIn(digest, summary)
        self.assertIn("9001", summary)

    def test_logs_receipt_and_summary_hold_no_local_path_or_token(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        texts = [out, err, (self.f.runner / "module-release-receipt.json").read_text(encoding="utf-8"),
                 (self.f.runner / "summary.md").read_text(encoding="utf-8"),
                 (self.f.runner / "event.json").read_text(encoding="utf-8"),
                 self.f.manifest_bytes.decode("utf-8"), self.f.jar.decode("latin-1")]
        for text in texts:
            self.assertNotIn(self.tmp, text)
            self.assertNotIn("fixture-catalogue-token-0000", text)
            self.assertNotIn("fixture-gh-token-0000", text)

    def test_metadata_is_fetched_fresh_immediately_before_the_single_install(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        sequence = [c for c in self.f.calls() if c["tool"] in ("npm", "https", "ultikits", "gh")]
        registry = [i for i, c in enumerate(sequence) if c.get("url") == REGISTRY_URL]
        install = [i for i, c in enumerate(sequence) if c["tool"] == "npm"]
        self.assertTrue(registry and install)
        self.assertEqual(registry[-1] + 1, install[0], "the metadata check is the call right before install")

    def test_routine_push_through_the_wrapper_installs_and_publishes_nothing(self):
        status, out, err = self.f.run_wrapper(GITHUB_EVENT_NAME="push")
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(self.f.calls(), [], "a routine push makes no external call at all")


class DispatchRefusalTests(FixtureBase):
    """Every input, dispatcher, event, ref and attempt refusal happens before any install or write."""

    def test_missing_input_is_refused(self):
        inputs = dict(self.f.inputs)
        del inputs["installation_id"]
        self.f.write_event(inputs)
        self.assert_refused(self.f.run_wrapper(), "inputs_missing_key")

    def test_mismatched_artifact_digest_is_refused(self):
        self.f.write_event(dict(self.f.inputs, artifact_sha256="0" * 64))
        self.assert_refused(self.f.run_wrapper(), "artifact_sha256_mismatch")

    def test_mismatched_manifest_digest_is_refused(self):
        self.f.write_event(dict(self.f.inputs, manifest_sha256="0" * 64))
        self.assert_refused(self.f.run_wrapper(), "manifest_sha256_mismatch")

    def test_staged_asset_whose_bytes_differ_is_refused(self):
        data = bytearray(self.f.jar)
        data[len(data) // 2] ^= 0x01
        (self.f.state / "assets" / "11").write_bytes(bytes(data))
        self.assert_refused(self.f.run_wrapper(), "artifact_sha256_mismatch")

    def test_wrongly_targeted_draft_is_refused(self):
        self.f.update_github(lambda g: g["releases"][DRAFT_ID].update(target_commitish="e" * 40))
        self.assert_refused(self.f.run_wrapper(), "release_target_mismatch")

    def test_published_release_whose_tag_resolves_elsewhere_is_refused(self):
        def mutate(g):
            g["releases"][DRAFT_ID]["draft"] = False
            g["tags"]["v" + VERSION] = "e" * 40
        self.f.update_github(mutate)
        status, out, err = self.f.run_wrapper()
        self.assertNotEqual(status, 0, msg=out + err)
        self.assertIn("tag_elsewhere", err)
        self.assertEqual(self.f.install_calls(), [])
        self.assertEqual(self.f.calls("ultikits"), [])
        self.assertEqual(self.f.github_writes(), [])

    def test_another_triggering_account_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_TRIGGERING_ACTOR="someone-else"), "actor_not_maintainer")

    def test_missing_triggering_account_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_TRIGGERING_ACTOR=None), "actor_not_maintainer")

    def test_another_event_is_refused_by_the_adapter_itself(self):
        self.assert_refused(self.f.run_adapter(GITHUB_EVENT_NAME="push"), "event_not_dispatch")

    def test_a_non_default_ref_is_refused(self):
        self.f.write_event(self.f.inputs, ref="refs/heads/feature")
        self.assert_refused(self.f.run_wrapper(GITHUB_REF="refs/heads/feature"), "ref_not_default")

    def test_rerun_attempt_two_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_RUN_ATTEMPT="2"), "rerun_refused")

    def test_another_repository_is_refused(self):
        self.assert_refused(self.f.run_wrapper(GITHUB_REPOSITORY="UltiKits/Other"), "repository_mismatch")

    def test_identity_is_never_derived_from_the_repository_name(self):
        self.assert_refused(self.f.run_adapter(args=[
            "--repo", REPO, "--identify-string", "ultibackup-lowercased-repo", "--default-branch", "master",
            "--maintainer", MAINTAINER, "--jar-prefix", PREFIX, "--helper-sha256", self.f.helper_sha256()]),
            "identify_string_mismatch")


class DottedIdentityTests(unittest.TestCase):
    """UltiKits' identity is ultikits.kits; it is passed and compared exactly, never derived."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="p21-module-release-id-")
        self.f = PublishFixture(Path(self.tmp) / "fx", identify="ultikits.kits")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_exact_dotted_identity_publishes_unchanged(self):
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        argv = self.f.publish_calls()[0]["argv"]
        self.assertEqual(argv[argv.index("--id") + 1], "ultikits.kits")
        self.assertEqual(self.f.catalogue()["versions"][-1]["identify"], "ultikits.kits")


def publish_release(f):
    """GitHub side already complete: the accepted draft was published at its source commit."""
    def mutate(g):
        g["releases"][DRAFT_ID]["draft"] = False
        g["tags"]["v" + VERSION] = f.head
    f.update_github(mutate)


def serve_version(f, data=None, level=630):
    """Catalogue side already holding this version (the accepted bytes unless data says otherwise)."""
    name = "%s-%s.jar" % (f.identify, VERSION)
    (f.state / "catalogue-files" / name).write_bytes(f.jar if data is None else data)
    f.update_catalogue(lambda c: c["versions"].append({"version": VERSION, "level": level, "file": name,
                                                       "identify": f.identify}))


class RecoveryMatrixTests(FixtureBase):
    """GitHub and the catalogue are reconciled independently; all four remote-state combinations."""

    def test_draft_and_absent_catalogue_writes_both_sides(self):
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        receipt = self.f.receipt()
        self.assertEqual((receipt["release_before"], receipt["release_outcome"]), ("draft", "published"))
        self.assertEqual((receipt["catalogue_before"], receipt["catalogue_outcome"]), ("absent", "uploaded"))

    def test_published_release_with_missing_catalogue_uploads_only_the_catalogue(self):
        publish_release(self.f)
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(self.f.github_writes(), [], "an identical published release is left alone")
        self.assertEqual(len(self.f.install_calls()), 1)
        self.assertEqual(len(self.f.publish_calls()), 1)
        receipt = self.f.receipt()
        self.assertEqual((receipt["release_before"], receipt["release_outcome"]), ("published", "already-published"))
        self.assertEqual(receipt["catalogue_outcome"], "uploaded")
        self.assertEqual(self.f.calls("mvn"), [])

    def test_catalogue_only_success_is_reconciled_by_publishing_the_draft(self):
        serve_version(self.f)
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(self.f.install_calls(), [], "nothing to upload, so the CLI is never installed")
        self.assertEqual(self.f.calls("ultikits"), [])
        self.assertEqual(len(self.f.github_writes()), 1)
        self.assertFalse(self.f.github()["releases"][DRAFT_ID]["draft"])
        receipt = self.f.receipt()
        self.assertEqual((receipt["catalogue_before"], receipt["catalogue_outcome"]), ("identical", "already-served"))
        self.assertEqual(receipt["release_outcome"], "published")

    def test_completed_identical_publication_is_a_no_op(self):
        publish_release(self.f)
        serve_version(self.f)
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(self.f.install_calls(), [])
        self.assertEqual(self.f.calls("ultikits"), [])
        self.assertEqual(self.f.github_writes(), [])
        receipt = self.f.receipt()
        self.assertEqual((receipt["release_outcome"], receipt["catalogue_outcome"]),
                         ("already-published", "already-served"))
        self.assertEqual(receipt["result"], "complete")

    def test_catalogue_holding_this_version_with_different_bytes_blocks(self):
        serve_version(self.f, data=self.f.prior_jar)
        self.assert_refused(self.f.run_adapter(), "catalogue_version_occupied")

    def test_published_release_with_different_bytes_blocks(self):
        publish_release(self.f)
        (self.f.state / "assets" / "11").write_bytes(self.f.prior_jar)
        status, out, err = self.f.run_adapter()
        self.assertEqual(status, 1, msg=out + err)
        self.assertIn("artifact_sha256_mismatch", err)
        self.assertEqual(self.f.install_calls(), [])
        self.assertEqual(self.f.calls("ultikits"), [])

    def test_equal_version_is_accepted_only_for_the_same_bytes_at_level_630(self):
        serve_version(self.f, level=620)
        self.assert_refused(self.f.run_adapter(), "catalogue_version_occupied")


class PartialFailureTests(FixtureBase):
    """A failed side is observable, non-zero and recoverable by a fresh dispatch without another build."""

    def retry_with_new_installation(self, installation_id="inst-retry-0000000002"):
        self.f.flags = {}
        self.f.save_flags()
        (self.f.runner / "module-release-receipt.json").unlink()
        self.f.inputs = dict(self.f.inputs, installation_id=installation_id)
        self.f.write_event(self.f.inputs)
        return self.f.run_wrapper()

    def test_github_success_with_catalogue_error_is_non_zero_and_retry_uploads_only_the_catalogue(self):
        self.f.flags = {"upload_fail": True}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        receipt = self.f.receipt()
        self.assertEqual(receipt["result"], "partial")
        self.assertEqual((receipt["release_outcome"], receipt["catalogue_outcome"]), ("published", "error"))
        self.assertIn("RESULT=partial", out)
        self.assertFalse(self.f.github()["releases"][DRAFT_ID]["draft"])
        writes_before = len(self.f.github_writes())
        status, out, err = self.retry_with_new_installation()
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual(len(self.f.github_writes()), writes_before, "the retry writes nothing to GitHub")
        self.assertEqual(len(self.f.publish_calls()), 2)
        self.assertEqual((self.f.state / "catalogue-files" / ("%s-%s.jar" % (IDENTIFY, VERSION))).read_bytes(),
                         self.f.jar, "the retry reuses the staged asset; nothing is rebuilt")
        receipt = self.f.receipt()
        self.assertEqual(receipt["installation_id"], "inst-retry-0000000002")
        self.assertEqual((receipt["release_outcome"], receipt["catalogue_outcome"]), ("already-published", "uploaded"))
        self.assertEqual(self.f.calls("mvn"), [])

    def test_failed_release_publication_is_non_zero_and_leaves_the_catalogue_untouched(self):
        self.f.update_github(lambda g: g.setdefault("fail", {}).update(undraft=502))
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        self.assertEqual(self.f.calls("ultikits")[-1]["argv"], ["publish", "--help"])
        self.assertEqual((self.f.receipt()["release_outcome"], self.f.receipt()["catalogue_outcome"]),
                         ("error", "not-attempted"))

    def test_corrupted_catalogue_upload_is_caught_on_readback(self):
        self.f.flags = {"upload_corrupt": True}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        self.assertIn("served_bytes_mismatch", err)
        self.assertEqual(self.f.receipt()["catalogue_outcome"], "error")

    def test_level_630_must_select_the_new_bytes(self):
        self.f.flags = {"upload_level": 620}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        self.assertIn("level630_not_selected", err)

    def test_the_no_level_answer_must_stay_on_the_prior_version(self):
        self.f.flags = {"upload_shadows_no_level": True}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        self.assertIn("no_level_changed", err)

    def test_a_repeat_after_completion_is_a_no_op_with_a_fresh_installation(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        installs, writes = len(self.f.install_calls()), len(self.f.github_writes())
        status, out, err = self.retry_with_new_installation()
        self.assertEqual(status, 0, msg=out + err)
        self.assertEqual((len(self.f.install_calls()), len(self.f.github_writes())), (installs, writes))
        self.assertEqual(len(self.f.publish_calls()), 1, "served bytes are never uploaded or overwritten twice")


class ConcurrencyAndStaleStateTests(FixtureBase):
    """One publication per repository/version, and the remote is compared again immediately before writes."""

    def test_concurrent_publication_of_the_same_version_is_refused(self):
        lock = self.f.runner / "ultitools-module-release-locks" / ("UltiKits_UltiBackup-%s.lock" % VERSION)
        lock.mkdir(parents=True)
        self.assert_refused(self.f.run_wrapper(), "concurrent_run")
        self.assertTrue(lock.is_dir(), "another run's lock is never removed")

    def test_the_lock_is_released_after_a_run(self):
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 0, msg=out + err)
        locks = self.f.runner / "ultitools-module-release-locks"
        self.assertEqual(list(locks.iterdir()) if locks.exists() else [], [])

    def test_default_branch_moving_before_the_first_write_blocks(self):
        self.f.update_github(lambda g: g.update(head_sequence=[self.f.head, "d" * 40]))
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 1, msg=out + err)
        self.assertIn("stale_default_head", err)
        self.assertEqual(self.f.github_writes(), [])
        self.assertEqual(self.f.publish_calls(), [])

    def test_draft_changing_before_the_first_write_blocks(self):
        self.f.update_github(lambda g: g.update(on_release_read={"2": {"target_commitish": "d" * 40}}))
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 1, msg=out + err)
        self.assertIn("release_target_mismatch", err)
        self.assertEqual(self.f.github_writes(), [])
        self.assertEqual(self.f.publish_calls(), [])

    def test_catalogue_occupied_by_another_writer_before_upload_blocks_without_overwrite(self):
        self.f.flags = {"install_side_effect_catalogue": {"version": VERSION, "file": "other-writer.jar",
                                                          "bytes": "different bytes"}}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 3, msg=out + err)
        self.assertIn("catalogue_version_occupied", err)
        self.assertEqual(self.f.publish_calls(), [], "a different served binary is never overwritten")
        self.assertEqual((self.f.state / "catalogue-files" / "other-writer.jar").read_bytes(), b"different bytes")


class FailClosedTests(FixtureBase):
    """Transport, authentication and server errors are errors; every input refusal is zero-call."""

    def test_catalogue_outage_or_auth_failure_is_an_error_not_absence(self):
        for code in (503, 500, 401, 403):
            with self.subTest(status=code):
                self.f.flags = {"catalogue_status": code}
                self.f.save_flags()
                self.assert_refused(self.f.run_wrapper(), "catalogue_unavailable")

    def test_registry_outage_blocks_the_installation(self):
        self.f.flags = {"registry_status": 503}
        self.f.save_flags()
        self.assert_refused(self.f.run_wrapper(), "registry_unavailable")

    def test_github_read_failure_blocks(self):
        self.f.update_github(lambda g: g.setdefault("fail", {}).update(get=502))
        self.assert_refused(self.f.run_wrapper(), "github_unavailable")

    def test_changed_package_metadata_blocks_the_installation(self):
        doc = json.loads((self.f.state / "npm-packument.json").read_text())
        doc["versions"]["1.2.0"]["scripts"]["postinstall"] = "node payload.js"
        PublishFixture.write_json(self.f.state / "npm-packument.json", doc)
        self.assert_refused(self.f.run_wrapper(), "package_metadata_changed")

    def test_wrong_helper_digest_blocks(self):
        self.f.wrapper_yml = WRAPPER_YML.replace(PINNED_SHA256["release_artifacts.py"], "0" * 64)
        self.assert_refused(self.f.run_wrapper(), None)

    def test_a_served_version_not_below_this_one_blocks(self):
        def mutate(c):
            name = "%s-3.0.0.jar" % IDENTIFY
            (self.f.state / "catalogue-files" / name).write_bytes(self.f.prior_jar)
            c["versions"].append({"version": "3.0.0", "level": 620, "file": name, "identify": IDENTIFY})
        self.f.update_catalogue(mutate)
        self.assert_refused(self.f.run_wrapper(), "version_not_greater")

    def test_a_served_version_that_cannot_be_ordered_blocks(self):
        self.f.update_catalogue(lambda c: c["versions"].append({"version": "1.5-beta", "level": 620,
                                                                "file": "x.jar", "identify": IDENTIFY}))
        self.assert_refused(self.f.run_wrapper(), "catalogue_version_not_comparable")

    def test_a_module_missing_from_the_catalogue_is_never_created(self):
        self.f.update_catalogue(lambda c: c.update(module=None))
        self.assert_refused(self.f.run_wrapper(), "catalogue_module_missing")

    def test_missing_catalogue_token_blocks_before_installation(self):
        self.assert_refused(self.f.run_wrapper(ULTIKITS_TOKEN=None), "catalogue_token_missing")

    def test_an_extra_input_is_refused(self):
        self.f.write_event(dict(self.f.inputs, note="publish please"))
        self.assert_refused(self.f.run_wrapper(), "inputs_unknown_key")

    def test_each_malformed_input_is_refused(self):
        for key in INPUT_KEYS:
            with self.subTest(key=key):
                self.f.write_event(dict(self.f.inputs, **{key: self.f.inputs[key] + "!"}))
                self.assert_refused(self.f.run_wrapper(), "input_format")

    def test_shell_payloads_in_every_input_are_refused_and_never_executed(self):
        canary = self.f.root / "canary"
        payloads = ["$(touch %s)" % canary, "`touch %s`" % canary, "x; touch %s" % canary,
                    "x' && touch %s && '" % canary, "x\ntouch %s" % canary, "%s\n" % "a"]
        for key in INPUT_KEYS:
            for payload in payloads:
                with self.subTest(key=key, payload=payload):
                    value = self.f.inputs[key] + payload if payload != "a\n" else self.f.inputs[key] + "\n"
                    self.f.write_event(dict(self.f.inputs, **{key: value}))
                    status, out, err = self.f.run_wrapper()
                    self.assertNotEqual(status, 0, msg=out + err)
                    self.assertFalse(canary.exists(), "an input was executed as shell")
                    self.assertNotIn("touch", out + err, "raw input text never reaches the log")
                    self.assert_nothing_installed_or_written()

    def test_non_string_duplicate_and_empty_inputs_are_refused(self):
        cases = {
            "number": json.dumps({"inputs": dict(self.f.inputs, draft_release_id=777001)}).encode(),
            "empty": json.dumps({"inputs": dict(self.f.inputs, installation_id="")}).encode(),
            "duplicate": ('{"inputs": {"source_head": "%s", "source_head": "%s"}}'
                          % (self.f.head, "e" * 40)).encode(),
            "not json": b"\xff\xfe not json",
            "empty file": b"",
            "no inputs": b'{"ref": "refs/heads/master"}',
        }
        for label, raw in cases.items():
            with self.subTest(case=label):
                self.f.write_event(None, raw=raw)
                self.assert_refused(self.f.run_wrapper())

    def test_missing_or_extra_draft_assets_block(self):
        cases = {"missing": lambda r: r["assets"].pop(),
                 "extra": lambda r: r["assets"].append({"id": 13, "name": "notes.txt", "file": "assets/11"})}
        for label, mutate in cases.items():
            with self.subTest(case=label):
                self.f.stage_github()
                self.f.update_github(lambda g: mutate(g["releases"][DRAFT_ID]))
                self.assert_refused(self.f.run_wrapper(), "release_assets_mismatch")

    def test_a_missing_draft_blocks(self):
        self.f.update_github(lambda g: g["releases"].pop(DRAFT_ID))
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 1, msg=out + err)
        self.assertIn("github_unavailable", err)
        self.assertEqual(self.f.install_calls(), [])

    def test_an_installed_cli_without_the_documented_flags_writes_nothing(self):
        self.f.flags = {"cli_help": "Usage: ultikits publish [options]\n  --version <v>\n"}
        self.f.save_flags()
        status, out, err = self.f.run_wrapper()
        self.assertEqual(status, 1, msg=out + err)
        self.assertIn("cli_flags_unknown", err)
        self.assertEqual(self.f.github_writes(), [])
        self.assertEqual(self.f.publish_calls(), [])

    def test_changelog_text_is_passed_as_one_literal_argument(self):
        canary = self.f.root / "changelog-canary"
        text = "## 2.0.0\n- $(touch %s) `touch %s`\n" % (canary, canary)
        (self.f.runner / "notes.md").write_text(text, encoding="utf-8")
        status, out, err = self.f.run_adapter("--changelog-file", str(self.f.runner / "notes.md"))
        self.assertEqual(status, 0, msg=out + err)
        argv = self.f.publish_calls()[0]["argv"]
        self.assertEqual(argv[argv.index("--changelog") + 1], text.rstrip("\n"))
        self.assertFalse(canary.exists())


class WrapperContractTests(unittest.TestCase):
    """The reference wrapper adds no permission or secret and serializes publications per repository."""

    def test_wrapper_keeps_contents_write_only_and_adds_no_secret(self):
        permissions = re.search(r"^    permissions:\n(?:      .*\n)+", WRAPPER_YML, re.M)
        assert permissions is not None, "the inspected permissions block must remain present"
        self.assertEqual(permissions.group(), "    permissions:\n      contents: write\n")
        self.assertNotIn("actions:", WRAPPER_YML)
        self.assertEqual(WRAPPER_YML.count("secrets."), 1, "only the existing catalogue secret")
        self.assertIn("group: module-publish-${{ github.repository }}", WRAPPER_YML)

    def test_wrapper_run_block_never_expands_dispatch_inputs(self):
        script = wrapper_script(WRAPPER_YML)
        self.assertNotIn("${{", script)
        self.assertNotIn("inputs.", script)
        self.assertNotIn("github.event", script)


class ActualWorkflowTests(unittest.TestCase):
    def test_dispatch_declares_exactly_seven_required_string_inputs_without_defaults(self):
        section = WRAPPER_YML.split("  workflow_dispatch:\n", 1)[1].split("\njobs:", 1)[0]
        declared = re.findall(r"^      ([a-z0-9_]+):", section, re.M)
        self.assertEqual(sorted(declared), sorted(INPUT_KEYS))
        self.assertEqual(section.count("required: true"), 7)
        self.assertEqual(section.count("type: string"), 7)
        self.assertNotIn("default:", section)

    def test_routine_runs_verify_and_never_use_floating_cli_or_tolerated_failure(self):
        self.assertIn("mvn -B clean verify", WRAPPER_YML)
        self.assertIn("python3 -I -m unittest discover", WRAPPER_YML)
        self.assertNotIn("continue-on-error", WRAPPER_YML)
        self.assertNotIn("npm install", WRAPPER_YML)
        self.assertNotIn("versions:set", WRAPPER_YML)
        self.assertNotIn("gh release create", WRAPPER_YML)


class FetchIntegrityTests(FixtureBase):
    def test_downloaded_adapter_or_helper_mutation_prevents_execution(self):
        for name in PINNED_SHA256:
            with self.subTest(file=name):
                self.f.flags = {"corrupt_download": name}
                self.f.save_flags()
                self.assert_refused(self.f.run_wrapper())

    def test_every_missing_input_is_refused(self):
        for key in INPUT_KEYS:
            with self.subTest(key=key):
                inputs = dict(self.f.inputs)
                del inputs[key]
                self.f.write_event(inputs)
                self.assert_refused(self.f.run_wrapper(), "inputs_missing_key")

    def test_wrong_source_version_and_draft_input_refuse(self):
        for key, value in (("source_head", "e" * 40), ("version", "9.0.0"), ("draft_release_id", "1234")):
            with self.subTest(key=key):
                self.f.write_event(dict(self.f.inputs, **{key: value}))
                self.assert_refused(self.f.run_wrapper())

    def test_a_non_dispatch_event_makes_no_external_call(self):
        status, out, err = self.f.run_wrapper(GITHUB_EVENT_NAME="pull_request")
        self.assertEqual(status, 0, out + err)
        self.assertEqual(self.f.calls(), [])


if __name__ == "__main__":
    unittest.main()
