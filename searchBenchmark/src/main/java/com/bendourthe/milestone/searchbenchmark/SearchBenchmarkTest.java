package com.bendourthe.milestone.searchbenchmark;
import android.content.Intent;
import android.content.ComponentName;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SearchBenchmarkTest {
 @Test public void sameWorkloadDrawsWithinTwoHundredMillisecondsAtP95() throws Exception {
  var inst = InstrumentationRegistry.getInstrumentation();
  var ctx = inst.getContext();
  assertEquals(ctx.getPackageName(), inst.getTargetContext().getPackageName());
  assertTrue("Benchmark controller is emulator-only", android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"));
  String id = InstrumentationRegistry.getArguments().getString("run_id");
  assertNotNull("Use scripts/validate.py --profile platform for artifact-bound execution", id);
  assertEquals(UUID.fromString(id).toString(), id);
  String expectedTarget = InstrumentationRegistry.getArguments().getString("expectedTargetSha256");
  String expectedController = InstrumentationRegistry.getArguments().getString("expectedControllerSha256");
  assertNotNull(expectedTarget); assertNotNull(expectedController);
  assertTrue(expectedTarget.matches("[0-9a-f]{64}")); assertTrue(expectedController.matches("[0-9a-f]{64}"));
  var targetInfo = ctx.getPackageManager().getApplicationInfo("com.bendourthe.milestone.benchmark", 0);
  assertEquals(0, targetInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE);
  assertEquals(expectedTarget, apkSha256(inst, targetInfo.sourceDir));
  assertEquals(expectedController, apkSha256(inst, ctx.getApplicationInfo().sourceDir));
  android.os.Bundle status = new android.os.Bundle(); status.putString("run_id", id); inst.sendStatus(0, status);
  long delay = Long.parseLong(InstrumentationRegistry.getArguments().getString("searchLatencyUiDelayMs", "0"));
  assertTrue(delay >= 0 && delay <= 1000);
  Intent intent = new Intent().setComponent(new ComponentName("com.bendourthe.milestone.benchmark", "com.bendourthe.milestone.ui.search.SearchBenchmarkActivity"))
   .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("run_id", id).putExtra("delay_ms", delay);
  var completed = new java.util.concurrent.CountDownLatch(1);
  android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
   @Override public void onReceive(android.content.Context context, Intent notification) {
    if (id.equals(notification.getStringExtra("run_id"))) completed.countDown();
   }
  };
  var filter = new android.content.IntentFilter("com.bendourthe.milestone.searchbenchmark.COMPLETE");
  if (android.os.Build.VERSION.SDK_INT >= 33) {
   ctx.registerReceiver(receiver, filter, "com.bendourthe.milestone.benchmark.PROBE", null, android.content.Context.RECEIVER_EXPORTED);
  } else {
   ctx.registerReceiver(receiver, filter, "com.bendourthe.milestone.benchmark.PROBE", null);
  }
  JSONObject result;
  try {
   ctx.startActivity(intent);
   assertTrue("Target completion signal missing", completed.await(150, java.util.concurrent.TimeUnit.SECONDS));
   String path = "/sdcard/Download/MileStoneSearchProbe/search-" + id + ".json";
   try (ParcelFileDescriptor fd = inst.getUiAutomation().executeShellCommand("cat " + path); FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
    result = new JSONObject(new String(readBytes(input), java.nio.charset.StandardCharsets.UTF_8));
   }
  } finally {
   ctx.unregisterReceiver(receiver);
   try (ParcelFileDescriptor fd = inst.getUiAutomation().executeShellCommand("am force-stop com.bendourthe.milestone.benchmark"); FileInputStream input = new FileInputStream(fd.getFileDescriptor())) { readBytes(input); }
  }
  assertNotNull("Fresh UUID result missing", result);
  assertEquals(id, result.getString("run_id"));
  assertEquals("com.bendourthe.milestone.benchmark", result.getString("target_package"));
  assertFalse(result.getBoolean("debuggable"));
  assertEquals("query-to-visible-v1", result.getString("scenario_contract"));
  assertTrue("Scenario completion: " + result.optString("error_type"), result.getBoolean("complete"));
  assertTrue(result.getBoolean("preservation_passed"));
  assertEquals(30, result.getInt("samples")); assertEquals(3, result.getInt("warmups"));
  assertEquals(25, result.getInt("programs")); assertEquals(100, result.getInt("cards")); assertEquals(2000, result.getInt("displayed_benefits"));
  assertEquals(delay, result.getLong("injected_ui_delay_ms"));
  android.util.Log.i("MileStoneSearchProbe", "result run_id=" + id + " p95_ms=" + result.getDouble("p95_ms"));
  assertTrue("Query-to-draw p95=" + result.getDouble("p95_ms"), result.getDouble("p95_ms") <= 200.0);
 }
 private static String apkSha256(android.app.Instrumentation inst, String path) throws Exception {
  assertTrue(path.matches("/data/app/[A-Za-z0-9_~/+=.-]+/base\\.apk"));
  var digest = java.security.MessageDigest.getInstance("SHA-256");
  long bytes = 0;
  // UiAutomation uses Runtime.exec(String), so quotes would become filename characters.
  try (ParcelFileDescriptor fd = inst.getUiAutomation().executeShellCommand("cat " + path); FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
   byte[] buffer = new byte[8192]; int count;
   while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); bytes += count; }
  }
  assertTrue("Installed APK bytes must be readable", bytes > 0);
  StringBuilder result = new StringBuilder();
  for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
  return result.toString();
 }

 private static byte[] readBytes(java.io.InputStream input) throws java.io.IOException {
  var output = new java.io.ByteArrayOutputStream();
  byte[] buffer = new byte[8192]; int count;
  while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
  return output.toByteArray();
 }

}
