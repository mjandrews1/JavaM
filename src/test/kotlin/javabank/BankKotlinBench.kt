package javabank

/**
 * Kotlin bank benchmark - same 4-corners x 10K as BankJavaBench / BankConcurrencyBench.
 * Usage: java -cp build:... javabank.BankKotlinBench [store] [corner]
 */
object BankKotlinBench {
    private val NS = longArrayOf(1, 10, 100, 1000, 10000)

    private fun makeStore(type: String): BankStore = when (type) {
        "sql" -> SqlBankStore(System.getProperty("java.io.tmpdir") + "/bankkt-" + System.nanoTime() + ".db")
        "cdb2" -> Cdb2BankStore("tools/cdb2pipe", "testdb", "local")
        else -> MemBankStore()
    }

    private fun runOne(store: BankStore, corner: Int, n: Long) {
        val bank = BankKotlin(store, System.nanoTime())
        if (corner == 1 || corner == 3) {
            bank.initAccount("0")
            bank.bench("0", n)
        } else {
            bank.init()
            bank.bench2(n)
        }
    }

    private fun runCorner(storeType: String, corner: Int, n: Long): Double {
        val t0 = System.nanoTime()
        if (corner <= 2) {
            makeStore(storeType).use { store -> runOne(store, corner, n) }
        } else {
            makeStore(storeType).use { store ->
                val ex = java.util.concurrent.Executors.newFixedThreadPool(2)
                val f1 = ex.submit<Void> { runOne(store, corner, n / 2); null }
                val f2 = ex.submit<Void> { runOne(store, corner, n - n / 2); null }
                f1.get(); f2.get()
                ex.shutdown()
            }
        }
        return (System.nanoTime() - t0) / 1e6
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val storeType = if (args.isNotEmpty()) args[0] else "mem"
        val onlyCorner = if (args.size > 1 && args[1] != "all") args[1].toInt() else 0
        val names = arrayOf("1 acct, 1 inst", "10 acct, 1 inst", "1 acct, 2 inst", "10 acct, 2 inst")

        println("Kotlin bank benchmark - store=$storeType")
        println("corner              N          time_ms    txn/ms")
        println("------------------  ---------  -------    ------")

        for (corner in 1..4) {
            if (onlyCorner > 0 && corner != onlyCorner) continue
            for (n in NS) {
                try {
                    val ms = runCorner(storeType, corner, n)
                    val txnPerMs = if (ms > 0) n / ms else 0.0
                    println("%-18s  %-9d  %7.1f   %6.1f".format(names[corner - 1], n, ms, txnPerMs))
                } catch (e: Throwable) {
                    println("%-18s  %-9d  ERROR: %s".format(names[corner - 1], n, e.message))
                }
            }
        }
        println("DONE")
        System.exit(0)
    }
}
