package com.example.aria.probe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * PHASE 1-PROBE — the three ways to obtain a SpeechRecognizer, in one place.
 *
 * WHY THIS FILE EXISTS: the whole point of the probe is to compare recogniser
 * PATHS while everything else stays identical. If the creation call were inlined
 * in the Activity, it would be easy to change an extra or an order by accident
 * between modes. Here each mode is one branch and nothing else differs.
 *
 * EVERY API USED HERE WAS CHECKED AGAINST AOSP SOURCE, NOT ASSUMED:
 *  - createSpeechRecognizer(Context)                  public, API 8
 *  - createSpeechRecognizer(Context, ComponentName)   public, API 8
 *  - isOnDeviceRecognitionAvailable(Context)          public, API 31
 *  - createOnDeviceSpeechRecognizer(Context)          public, API 31
 *  - RecognitionService.SERVICE_INTERFACE             public, API 8
 *  - PackageManager.queryIntentServices(Intent, int)  public, not deprecated
 *  - ResolveInfo.serviceInfo / ServiceInfo.name and .packageName (inherited
 *    from PackageItemInfo)                            public
 * The two API-31 members are behind an explicit SDK_INT check, because minSdk is
 * 26 and lint NewApi fails the build otherwise.
 */
object RecognizerTargets {

    /** A recogniser service installed on the device. */
    data class Service(val component: ComponentName, val label: String) {
        override fun toString(): String = label
    }

    /**
     * Every installed recognition service, discovered at runtime.
     *
     * DELIBERATELY NOT HARDCODED: hardcoding Google's or Samsung's package would
     * make this probe fail silently on any device where the vendor shipped a
     * differently-named recogniser, and would hide the very alternative we are
     * trying to test.
     *
     * `queryIntentServices(Intent, 0)` rather than a flag constant: 0 means "all
     * matching", and it needs no `GET_*` constant whose presence I would have to
     * verify. Package visibility is already satisfied by the <queries> entry for
     * android.speech.RecognitionService that the MAIN manifest declares.
     */
    fun installedServices(context: Context): List<Service> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolved = runCatching {
            context.packageManager.queryIntentServices(intent, 0)
        }.getOrNull().orEmpty()

        return resolved.mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val pkg = serviceInfo.packageName ?: return@mapNotNull null
            val name = serviceInfo.name ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(context.packageManager).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            Service(ComponentName(pkg, name), label)
        }.distinctBy { it.component.flattenToString() }
    }

    /** True when the platform has an on-device recogniser (API 31+). */
    fun isOnDeviceAvailable(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)
        } else {
            false
        }

    /**
     * Creates a recogniser for the requested mode, or returns null with a reason
     * the UI can show. The listener is attached by the caller so this function
     * stays a pure "which path" switch.
     *
     * @param mode "A", "B" or "C".
     * @param service required for mode B.
     */
    fun create(
        context: Context,
        mode: String,
        service: Service?
    ): Result<SpeechRecognizer> = runCatching {
        when (mode) {
            // MODE A — exactly what ARIA's own manager does today.
            "A" -> SpeechRecognizer.createSpeechRecognizer(context)

            // MODE B — pin a specific installed service, chosen at runtime.
            "B" -> {
                val component = service?.component
                    ?: error("mode B needs a service selected")
                SpeechRecognizer.createSpeechRecognizer(context, component)
            }

            // MODE C — the on-device recogniser, only where the platform has one.
            "C" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    error("on-device recogniser needs API 31+ (this device is API ${Build.VERSION.SDK_INT})")
                }
                if (!isOnDeviceAvailable(context)) {
                    error("on-device recogniser unavailable")
                }
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }

            else -> error("unknown mode $mode")
        }
    }

    /** Human-readable description of the resolved path, for the log line. */
    fun describe(mode: String, service: Service?): String = when (mode) {
        "B" -> service?.component?.flattenToShortString() ?: "?"
        "C" -> "on-device"
        else -> "system-default"
    }
}
