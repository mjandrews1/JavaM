package javabank

import java.util.Random

/**
 * Pure Kotlin bank - direct translation of BANK.m.
 * Same operations, same store pattern, no M engine overhead.
 */
class BankKotlin(private val store: BankStore, seed: Long = 42) {
    private val rng = Random(seed)

    companion object {
        fun c(acct: String) = "BANK\u0001C\u0001$acct"
        fun b(acct: String) = "BANK\u0001B\u0001$acct"
        fun n() = "BANK\u0001N"
        fun j(n: Long) = "BANK\u0001J\u0001$n"
        fun adv(a: String) = "BANK\u0001ADV\u0001$a"
        fun fee(a: String) = "BANK\u0001FEE\u0001$a"
    }

    fun init() {
        store.set("BANK\u0001CUR", "USD")
        store.setNum(n(), 0.0)
        store.kill("BANK\u0001C"); store.kill("BANK\u0001B")
        store.kill("BANK\u0001J"); store.kill("BANK\u0001ADV")
        store.kill("BANK\u0001FEE")
    }

    fun initAccount(acct: String) {
        store.setNum(c(acct), 0.0); store.setNum(b(acct), 0.0)
        store.setNum(adv(acct), 0.0); store.setNum(fee(acct), 0.0)
    }

    fun deposit(acct: String, amount: Double): Double {
        if (amount <= 0) return -1.0
        val cb = store.getNum(c(acct)) + amount
        store.setNum(c(acct), cb)
        store.setNum(b(acct), store.getNum(b(acct)) + amount)
        log("D", acct, amount, cb)
        return cb
    }

    fun withdraw(acct: String, amount: Double): Double {
        if (amount <= 0) return -1.0
        val wc = store.getNum(c(acct))
        if (wc < 0) return -1.0
        var wn = wc - amount
        var wf = 0.0
        if (wn < 0) { wf = 3.0; wn -= 3.0 }
        if (wn < -100) return -1.0
        store.setNum(c(acct), wn)
        store.setNum(b(acct), store.getNum(b(acct)) - amount + wf)
        if (wf > 0) {
            store.setNum(adv(acct), store.getNum(adv(acct)) + 1)
            store.setNum(fee(acct), store.getNum(fee(acct)) + wf)
            log("F", acct, wf, wn)
        }
        log("W", acct, amount, wn)
        return wn
    }

    private fun log(type: String, acct: String, amount: Double, balance: Double) {
        val n = store.increment(n())
        store.set(j(n.toLong()), "$type,$acct,${amount.toLong()},{balance.toLong()}")
    }

    fun bal(acct: String): Double = store.getNum(c(acct))

    fun stat2(acct: String): String = buildString {
        append("ACCT=$acct CHECK=${store.getNum(c(acct)).toLong()}")
        append(" BANK=${store.getNum(b(acct)).toLong()}")
        append(" ADV=${store.getNum(adv(acct)).toLong()}")
        append(" FEE=${store.getNum(fee(acct)).toLong()}")
    }

    fun stat(): String {
        val txn = store.getNum(n()).toLong()
        val chk = store.getNum(c("0")).toLong()
        val bank = store.getNum(b("0")).toLong()
        return "TXN=$txn CHECK=$chk BANK=$bank"
    }

    fun bench(acct: String, n: Long): String {
        repeat(n.toInt()) {
            val amount = (rng.nextInt(100) + 1).toDouble()
            if (rng.nextInt(2) == 0) deposit(acct, amount) else withdraw(acct, amount)
        }
        return stat2(acct)
    }

    fun bench2(n: Long): String {
        repeat(n.toInt()) {
            val acct = rng.nextInt(10).toString()
            val amount = (rng.nextInt(100) + 1).toDouble()
            if (rng.nextInt(2) == 0) deposit(acct, amount) else withdraw(acct, amount)
        }
        return "TXN=${store.getNum(n()).toLong()} ACCTS=10"
    }
}
