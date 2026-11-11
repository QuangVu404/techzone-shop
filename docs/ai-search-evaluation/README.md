# Search evaluation

This is a small development-set comparison, not an independent benchmark.
`src/test/resources/search-evaluation.tsv` contains sixteen manually authored
Vietnamese queries and one selected relevant product per query. The catalogue is
the 20-product `TechZoneStore` seed, not the crawled GearVN catalogue. Other
relevant products were not exhaustively labelled.

From the project root on Windows, after installing BGE-M3 using `setup-ai.bat`:

```powershell
.\build.bat
javac -source 17 -target 17 -encoding UTF-8 -cp target\classes -d target\test-classes src\test\java\vn\techzone\SearchComparisonBenchmark.java
java '-Dfile.encoding=UTF-8' -cp 'target\classes;target\test-classes;lib\*' vn.techzone.SearchComparisonBenchmark
```

The Java program warms the document embeddings, runs each query once for the
keyword and BGE-M3 paths using the same catalogue and filters, and writes
`results.tsv`, `summary.tsv` and `metadata.txt` here. Recall@3 is the proportion
of queries whose selected product appears in the first three results. NDCG@3 is
`1/log2(rank+1)` for that product when its rank is at most 3, otherwise zero;
the reported value is the mean across queries. Median and p95 use the
nearest-rank order statistic over sixteen observations.

Times cover in-process search, including BGE-M3 query inference and Java
filtering/ranking, but exclude model startup, HTTP, browser rendering and a
concurrent workload. They are not evidence that the latency NFR is met.
Results depend on the machine and other running processes.
