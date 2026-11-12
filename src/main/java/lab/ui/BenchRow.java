package lab.ui;

/** One line of bench/results.csv, as the table and chart want it. */
record BenchRow(String network, String mode, String workload, long payload, int iterations,
                long totalMs, long serverMs, long transferMs, double mbps, boolean ok) {}
