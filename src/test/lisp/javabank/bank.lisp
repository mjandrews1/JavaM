;;;; bank.lisp - Pure Common Lisp bank, direct translation of BANK.m
;;;; Uses ABCL Java interop to call BankStore interface.

(defpackage :javabank
  (:use :cl)
  (:export #:holder))

(in-package :javabank)

(defconstant +sep+ (code-char 1))

(defun key-c (acct) (format nil "BANKC~A" acct))
(defun key-b (acct) (format nil "BANKB~A" acct))
(defun key-n () (format nil "BANKN"))
(defun key-j (n) (format nil "BANKJ~A" n))
(defun key-adv (a) (format nil "BANKADV~A" a))
(defun key-fee (a) (format nil "BANKFEE~A" a))

;;; Store accessors - use set/get with string values to avoid primitive boxing
(defun store-get-num (store key)
  (let ((v (java:jcall "get" store key)))
    (if (or (null v) (null v))
        0.0d0
        (parse-integer v :junk-allowed t))))

(defun store-set-num (store key val)
  (java:jcall "set" store key (princ-to-string (floor val))))

(defun store-set (store key val)
  (java:jcall "set" store key val))

(defun store-incr (store key)
  (let ((cur (store-get-num store key)))
    (store-set-num store key (1+ cur))
    (1+ cur)))

(defun store-kill (store prefix)
  (java:jcall "kill" store prefix))

(defun holder ()
  (java:jstatic "holder" "javabank.BankAbcl"))

(defun bank-init (store)
  (store-set store (format nil "BANKCUR") "USD")
  (store-set-num store (key-n) 0)
  (store-kill store (format nil "BANKC"))
  (store-kill store (format nil "BANKB"))
  (store-kill store (format nil "BANKJ"))
  (store-kill store (format nil "BANKADV"))
  (store-kill store (format nil "BANKFEE")))

(defun bank-init-account (store acct)
  (store-set-num store (key-c acct) 0)
  (store-set-num store (key-b acct) 0)
  (store-set-num store (key-adv acct) 0)
  (store-set-num store (key-fee acct) 0))

(defun bank-deposit (store acct amount)
  (when (<= amount 0) (return-from bank-deposit -1.0d0))
  (let ((cb (+ (store-get-num store (key-c acct)) amount)))
    (store-set-num store (key-c acct) cb)
    (store-set-num store (key-b acct)
                   (+ (store-get-num store (key-b acct)) amount))
    (bank-log store "D" acct amount cb)
    cb))

(defun bank-withdraw (store acct amount)
  (when (<= amount 0) (return-from bank-withdraw -1.0d0))
  (let ((wc (store-get-num store (key-c acct))))
    (when (< wc 0) (return-from bank-withdraw -1.0d0))
    (let ((wn (- wc amount))
          (wf 0.0d0))
      (when (< wn 0) (setf wf 3.0d0) (decf wn 3.0d0))
      (when (< wn -100) (return-from bank-withdraw -1.0d0))
      (store-set-num store (key-c acct) wn)
      (store-set-num store (key-b acct)
                     (+ (- (store-get-num store (key-b acct)) amount) wf))
      (when (> wf 0)
        (store-set-num store (key-adv acct)
                       (1+ (store-get-num store (key-adv acct))))
        (store-set-num store (key-fee acct)
                       (+ (store-get-num store (key-fee acct)) wf))
        (bank-log store "F" acct wf wn))
      (bank-log store "W" acct amount wn)
      wn)))

(defun bank-log (store type acct amount balance)
  (let ((n (store-incr store (key-n))))
    (store-set store (key-j n)
               (format nil "~A,~A,~A,~A" type acct (floor amount) (floor balance)))))

(defun bank-stat2 (store acct)
  (format nil "ACCT=~A CHECK=~A BANK=~A ADV=~A FEE=~A"
          acct
          (store-get-num store (key-c acct))
          (store-get-num store (key-b acct))
          (store-get-num store (key-adv acct))
          (store-get-num store (key-fee acct))))

(defun bank-bench (store acct n)
  (dotimes (i n)
    (let ((amount (+ (random 100) 1))
          (t* (random 2)))
      (if (zerop t*)
          (bank-deposit store acct (float amount 1.0d0))
          (bank-withdraw store acct (float amount 1.0d0)))))
  (bank-stat2 store acct))

(defun bank-bench2 (store n)
  (dotimes (i n)
    (let ((acct (princ-to-string (random 10)))
          (amount (float (+ (random 100) 1) 1.0d0))
          (t* (random 2)))
      (if (zerop t*)
          (bank-deposit store acct amount)
          (bank-withdraw store acct amount))))
  (format nil "TXN=~A ACCTS=10" (store-get-num store (key-n))))
