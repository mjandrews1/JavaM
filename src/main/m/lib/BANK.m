BANK ; Double-entry bank with overdraft protection (USD)
 Q
 ; ^BANK("C")=checking  ^BANK("B")=bank  ^BANK("N")=txn count
 ; ^BANK("J",n)=journal  ^BANK("ADV")=advances  ^BANK("FEE")=total fees
 ; Invariant: BANK-CHECK=2*FEE

INIT ; Initialize bank
 S ^BANK("CUR")="USD"
 S ^BANK("C")=0 S ^BANK("B")=0 S ^BANK("N")=0
 S ^BANK("ADV")=0 S ^BANK("FEE")=0
 K ^BANK("J")
 Q

DEP(%A) ; Deposit %A. Returns checking or -1.
 I %A'>0 Q -1
 S ^BANK("C")=$G(^BANK("C"))+%A
 S ^BANK("B")=$G(^BANK("B"))+%A
 D LOG("D",%A)
 Q ^BANK("C")

WDR(%A) ; Withdraw %A. Returns checking or -1.
 I %A'>0 Q -1
 N %C,%N,%F
 S %C=$G(^BANK("C"))
 I %C<0 Q -1
 S %N=%C-%A
 S %F=0
 I %N<0 S %F=3,%N=%N-3
 I %N<-100 Q -1
 S ^BANK("C")=%N
 S ^BANK("B")=$G(^BANK("B"))-%A+%F
 I %F>0 S ^BANK("ADV")=$G(^BANK("ADV"))+1,^BANK("FEE")=$G(^BANK("FEE"))+%F
 D LOG("W",%A)
 I %F>0 D LOG("F",%F)
 Q ^BANK("C")

LOG(%T,%A) ; Append journal entry (immutable).
 N %LGN
 S %LGN=$I(^BANK("N"))
 S ^BANK("J",%LGN)=%T_","_%A_","_$G(^BANK("C"))
 Q

BAL() ; Checking balance
 Q $G(^BANK("C"))

BBAL() ; Bank balance
 Q $G(^BANK("B"))

RECON() ; Reconcile journal vs balances. Returns 1 if OK.
 Q $$RECON2("",0,0,0)

RECON2(%RI,%RD,%RW,%RF) ; Recursive journal walk.
 N %RR,%RT,%RA,%NX
 S %NX=$O(^BANK("J",%RI))
 I %NX="" G RECON2CHK
 S %RR=^BANK("J",%NX)
 S %RT=$E(%RR,1)
 S %RA=+$P(%RR,",",2)
 I %RT="D" Q $$RECON2(%NX,%RD+%RA,%RW,%RF)
 I %RT="W" Q $$RECON2(%NX,%RD,%RW+%RA,%RF)
 I %RT="F" Q $$RECON2(%NX,%RD,%RW,%RF+%RA)
 Q $$RECON2(%NX,%RD,%RW,%RF)
RECON2CHK
 I $G(^BANK("C"))=%RD-%RW-%RF Q 1
 Q 0

JRN() ; Return journal as string
 Q $$JRN2("","")

JRN2(%JI,%JR) ; Recursive journal to string.
 N %NX
 S %NX=$O(^BANK("J",%JI))
 I %NX="" Q %JR
 Q $$JRN2(%NX,%JR_%NX_":"_^BANK("J",%NX)_"!")

STAT() ; Status string
 Q "USD CHECK="_$G(^BANK("C"))_" BANK="_$G(^BANK("B"))_" TXN="_$G(^BANK("N"))_" ADV="_$G(^BANK("ADV"))_" FEE="_$G(^BANK("FEE"))

BENCH(%N) ; Run %N random transactions. Returns status.
 N %I,%T,%A
 D INIT
 F %I=1:1:%N D
 . S %T=$R(2)
 . S %A=$R(100)+1
 . I %T=0 D DEP(%A)
 . E  D WDR(%A)
 Q $$STAT
