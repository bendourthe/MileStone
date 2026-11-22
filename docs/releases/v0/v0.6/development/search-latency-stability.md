# Search Latency Stability

## Scope

On 2026-11-22 the owner approved correcting the hosted Search check before merchant offers, carrying the four v0.5.1 publication records into the replacement PR and closing PR30. This correction does not approve merchant-offer enrollment or issuer automation. Feature work remains subject to the queued plan refresh and owner decisions.

The correction uses branch `fix/hosted-search-latency`, based on the publication-record commit `40d030a73a63d63f7a97e76b1235503e61f696c6`, whose parent is integrated develop `30d7efe8c5f35d1173f0b61f37f2537cce45e856`. The primary checkout and its two unrelated local commits are preserved.

## Diagnosis

Both attempts of hosted run [45018813619](https://github.com/bendourthe/MileStone/actions/runs/45018813619) failed the same unchanged workload: p95 205.056524 ms and 271.504339 ms. Downloaded validation artifacts `14753627648` and `14755612222` retain the original observations. The slowest samples include substantial time both before result publication and between publication and visible draw, without garbage collection. Failure-only diagnostic reruns measured 206.045771 ms and 226.488975 ms; they are not qualification results. Each host exposed four logical processors and two physical cores, and each guest exposed two processors.

R8 optimizes the APK, but the existing controller never selected Android Runtime (ART) compilation state. Three query warmups do not establish that methods have already been compiled. Android documents [full compilation](https://developer.android.com/reference/androidx/benchmark/macro/CompilationMode) as a way to remove just-in-time compilation noise, while warning that it does not represent the usual fresh-install state. Compilation is a candidate source of variance, not a proven explanation for every scheduling or rendering delay in these runs.

Offline decoding with Perfetto v58.2 confirms CPU contention in the separate hosted diagnostic runs. Their trace hashes and run UUIDs match their capture receipts, and the decoder reports no nonzero warning/error statistics. The first trace fully covers 28 of 30 measured phases and the second covers 27 of 30; uncovered phases are excluded from analysis. In fully covered title sample 7 of the second diagnostic, the main thread spends 121.005 ms runnable but unscheduled and 40.500 ms running, while RenderThread spends 113.796 ms running and 70.793 ms runnable. The JIT thread runs for 13.934 ms. The equivalent first diagnostic sample has 72.599 ms of main-thread runnable wait and 111.374 ms of render execution. These observations establish scheduler contention in the traced workloads; they do not attribute every original untraced sample to one cause.

The guest exposed only two CPUs despite four logical host CPUs. [GitHub's public Linux runner specification](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) provides four CPUs, and this repository is public. Leaving the emulator at its two-CPU default unnecessarily constrains the concurrent rendering, UI and matching workload.

## Correction

After verifying the installed APK hashes and before launching the synthetic activity, the controller runs `cmd package compile -f -m speed com.bendourthe.milestone.benchmark`. It requires the exact `Success` response and reports `runtime_compilation=speed` in instrumentation status. A compilation failure prevents measurement. No issuer package is compiled or launched.

CI now launches the emulator with explicit `-cores 4`, requires at least four host CPUs, records the host count and guest online CPU list, and requires guest CPUs `0-3` before tests start. This uses the existing standard runner, without a paid runner change. CI impact is limited to emulator provisioning and evidence; triggers, permissions, aggregate required checks and the repository-owned validation profiles are unchanged.

The fixed configuration defines a controlled emulator regression check. It does not prove physical-device or fresh-install performance. The production animated background, Search matching and content, expected results, three warmups, all 30 observations, elapsed wall-clock measurement, visible-draw endpoint, preservation assertions and 200 ms p95 threshold remain unchanged. No samples are discarded and no pass-until-green retry is added.

## Verification

- The existing benchmark-runner unit suite passes all 12 tests, including rejecting slow, incomplete, malformed and unrelated metrics.
- The first local baseline attempt failed before a workload result: the emulator runner reported `Instrumentation run failed due to failed to attach`. Gradle returned zero, but metric collection rejected the missing JSON. This is not a latency result.
- Direct baseline preparation installed only the two synthetic benchmark packages. Host CPU stayed above the 15 percent idle guard for 40 seconds, so no timing run started. The test emulator was then rebooted without clearing data.
- The modified controller builds. On the restarted emulator, a first run completed with p95 112.370605 ms but was correctly rejected for a 1080x2400 viewport. Restoring the required 1080x2340 viewport produced a complete accepted workload at p95 71.613344 ms, with all samples and preservation intact.
- A separate 250 ms UI-delay control completed at p95 279.329905 ms and failed the controller's unchanged `Query-to-draw p95=` assertion. The normal result is retained separately from the intentionally failing control.
- The original controller was temporarily built for a diagnostic comparison, then the final source was restored through edit-guard checks. Reset first failed because Gradle had removed the fixture app; installing that synthetic target before reset corrected setup. The original controller then completed at p95 74.806978 ms. This comparison does not establish compilation as the sole root cause and is not offered as hosted qualification.
- Explicit four-CPU emulator startup is locally observed with guest CPUs `0-3` and the preserved 1080x2340 override. The restored final source passes the artifact-bound runner and its one instrumented test at p95 32.3279 ms, with all 30 samples, three warmups, complete results and preserved storage. The local host was loaded, so these timings are local functional evidence and do not establish hosted reliability.
- Normal, delay-control, original-controller and final-source comparisons all use target APK SHA-256 `3bc09a771ede2fea32ccb4502fea6a962c5b1caa221cd9319aeacc7047eb9eaa`. No production app code changed. The final local result is retained under `reports/search-stability/final-four-cpus/`; the deliberately failing control is retained separately under `reports/search-stability/delay-control/`.
- Current fast validation passes privacy, syntax and handbook freshness. The script suite runs 58 tests with zero failures/errors and one Windows skip of a real POSIX process-group test. The generated handbook passes 2,630 Chromium checks with zero findings. Workflow YAML parsing and Bash syntax pass for all seven shell run blocks. The owner phone is untouched.
- Hosted qualification remains pending. A single local pass cannot establish reliability across hosted loads.

## Publication

PR30 remains open until a replacement PR contains all four publication-record files. No merge is permitted while required CI is red. This document records the current experiment and does not claim the hosted reliability issue is resolved.
