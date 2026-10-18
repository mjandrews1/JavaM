PEMDAS ; school-precedence arithmetic evaluator (recursive descent)
 Q

EVAL(%E)
 S %PSP=1
 Q $$ADD(%E)

 ; --- left-associative + and - ---
ADD(%E)
 Q $$ADDL(%E,$$MUL(%E))

ADDL(%E,%AL)
 I %PSP>$L(%E) Q %AL
 I $E(%E,%PSP)="+" S %PSP=%PSP+1 Q $$ADDL(%E,%AL+$$MUL(%E))
 I $E(%E,%PSP)="-" S %PSP=%PSP+1 Q $$ADDL(%E,%AL-$$MUL(%E))
 Q %AL

 ; --- left-associative * and / ---
MUL(%E)
 Q $$MULL(%E,$$POW(%E))

MULL(%E,%ML)
 I %PSP>$L(%E) Q %ML
 I $E(%E,%PSP)="*" S %PSP=%PSP+1 Q $$MULL(%E,%ML*$$POW(%E))
 I $E(%E,%PSP)="/" S %PSP=%PSP+1 Q $$MULL(%E,%ML\$$POW(%E))
 Q %ML

 ; --- right-associative ** ---
POW(%E)
 N %PB
 S %PB=$$UN(%E)
 I $E(%E,%PSP,%PSP+1)="**" S %PSP=%PSP+2 Q $$PI(%PB,$$POW(%E))
 Q %PB

 ; --- unary + and - ---
UN(%E)
 I %PSP>$L(%E) Q 0
 I $E(%E,%PSP)="-" S %PSP=%PSP+1 Q $$NEG($$UN(%E))
 I $E(%E,%PSP)="+" S %PSP=%PSP+1 Q $$UN(%E)
 Q $$AT(%E)

NEG(%X)
 Q 0-%X

 ; --- atom: (expr) or number ---
AT(%E)
 N %AV,%AS
 I %PSP>$L(%E) Q 0
 I $E(%E,%PSP)="(" S %PSP=%PSP+1 S %AV=$$ADD(%E) I $E(%E,%PSP)=")" S %PSP=%PSP+1 Q %AV
 S %AS=%PSP
 Q $$NUM(%E,%AS)

NUM(%E,%NS)
 I %PSP>$L(%E) Q +$E(%E,%NS,%PSP-1)
 I $E(%E,%PSP)?1N S %PSP=%PSP+1 Q $$NUM(%E,%NS)
 I $E(%E,%PSP)="." S %PSP=%PSP+1 Q $$FRAC(%E,%NS)
 Q +$E(%E,%NS,%PSP-1)

FRAC(%E,%FS)
 I %PSP>$L(%E) Q +$E(%E,%FS,%PSP-1)
 I $E(%E,%PSP)?1N S %PSP=%PSP+1 Q $$FRAC(%E,%FS)
 Q +$E(%E,%FS,%PSP-1)

 ; --- integer power ---
PI(%PB,%PE)
 N %ZR,%ZI
 I %PE<0 Q 0
 I %PE=0 Q 1
 S %ZR=1
 F %ZI=1:1:%PE S %ZR=%ZR*%PB
 Q %ZR
