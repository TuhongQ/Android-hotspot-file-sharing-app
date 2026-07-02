package cn.local.bridgeshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidLicenseManager {
    private static final String PREFS = "BridgeShareLicense";
    private static final String KEY_LICENSE = "license_key";
    private static final String KEY_TRIAL_START = "trial_start";
    private static final String KEY_TRIAL_EXPIRES = "trial_expires";
    private static final long TRIAL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzF0kVcRC4TowVjCpA1Qp" +
            "k2Vj1C44NOpVCQVBPCYTdEyxFE0kLNQ4fD9AHxcrPaFa040qnPeRedlB2f/Pjuwd" +
            "OVtAGxBvJKmo2sBG/onAD7uI3qbh7kvxq58d78WZB0hdTYZL/Ucne6eTXtwMnwKX" +
            "ETIdDLeO0fixOorybBrrF4V935HIBQTXKJRFcidkfpgAxtqI8sCAe0QnXhT5vAVi" +
            "StmACV9cCHQUJ9oo90Hgu5tXMrVoH6PRtnj2NVIULHRWw503ttngLYSXh374GxNF" +
            "m5B4bYJGCo04RtXhASjs1GlkbGmOLtkVEG6WWAxsd6KamnO56OJqENjRMHhGnAZ1" +
            "jwIDAQAB";

    private final SharedPreferences preferences;
    private final String machineCode;
    private String lastReason = "";

    public AndroidLicenseManager(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        machineCode = createMachineCode(context);
    }

    public String machineCode() {
        return machineCode;
    }

    public boolean canUse() {
        return isLicensed() || isTrialActive();
    }

    public boolean isLicensed() {
        String key = preferences.getString(KEY_LICENSE, "");
        if (key == null || key.trim().isEmpty()) {
            lastReason = "Not activated";
            return false;
        }
        boolean valid = verify(key.trim());
        if (!valid && lastReason.trim().isEmpty()) lastReason = "Invalid registration code";
        return valid;
    }

    public boolean activate(String key) {
        String clean = key == null ? "" : key.trim().replaceAll("\\s+", "");
        if (!verify(clean)) return false;
        preferences.edit().putString(KEY_LICENSE, clean).apply();
        lastReason = "Activated";
        return true;
    }

    public void clearLicense() {
        preferences.edit().remove(KEY_LICENSE).apply();
    }

    public boolean canStartTrial() {
        return preferences.getLong(KEY_TRIAL_START, 0) == 0;
    }

    public void startTrial() {
        if (!canStartTrial()) return;
        long now = System.currentTimeMillis();
        preferences.edit()
                .putLong(KEY_TRIAL_START, now)
                .putLong(KEY_TRIAL_EXPIRES, now + TRIAL_MS)
                .apply();
    }

    public boolean isTrialActive() {
        long expires = preferences.getLong(KEY_TRIAL_EXPIRES, 0);
        return expires > System.currentTimeMillis();
    }

    public boolean trialExpired() {
        long start = preferences.getLong(KEY_TRIAL_START, 0);
        long expires = preferences.getLong(KEY_TRIAL_EXPIRES, 0);
        return start > 0 && expires <= System.currentTimeMillis();
    }

    public int trialDaysRemaining() {
        long expires = preferences.getLong(KEY_TRIAL_EXPIRES, 0);
        long remaining = expires - System.currentTimeMillis();
        if (remaining <= 0) return 0;
        return Math.max(1, (int) Math.ceil(remaining / (24d * 60d * 60d * 1000d)));
    }

    public String statusText() {
        if (isLicensed()) return "Licensed";
        if (isTrialActive()) return "Free trial · " + trialDaysRemaining() + " day" + (trialDaysRemaining() == 1 ? "" : "s") + " remaining";
        if (trialExpired()) return "Trial expired. Registration is required.";
        return "Start a 7-day trial or activate with a registration code.";
    }

    public String lastReason() {
        return lastReason;
    }

    private boolean verify(String key) {
        try {
            String[] parts = key.split("\\.");
            if (parts.length != 3 || !"BS1".equals(parts[0])) {
                lastReason = "Invalid registration code format";
                return false;
            }
            String payload = new String(Base64.decode(padBase64Url(parts[1]), Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8);
            String licensedMachine = jsonValue(payload, "machineCode");
            String expiresAt = jsonValue(payload, "expiresAt");
            if (!machineCode.equals(licensedMachine)) {
                lastReason = "This code belongs to another device";
                return false;
            }
            if (expiresAt != null && !expiresAt.isEmpty() && parseIsoTime(expiresAt) < System.currentTimeMillis()) {
                lastReason = "Registration code expired";
                return false;
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            byte[] keyBytes = Base64.decode(PUBLIC_KEY, Base64.DEFAULT);
            signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes)));
            signature.update(parts[1].getBytes(StandardCharsets.UTF_8));
            boolean ok = signature.verify(Base64.decode(padBase64Url(parts[2]), Base64.URL_SAFE | Base64.NO_WRAP));
            lastReason = ok ? "" : "Invalid registration code signature";
            return ok;
        } catch (Exception e) {
            lastReason = "Registration code could not be parsed";
            return false;
        }
    }

    private static String jsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    private static String padBase64Url(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) return value;
        StringBuilder builder = new StringBuilder(value);
        for (int i = remainder; i < 4; i++) builder.append('=');
        return builder.toString();
    }

    private static long parseIsoTime(String value) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.parse(value).getTime();
    }

    private static String createMachineCode(Context context) {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String source = "bridge-share-android-v1|" + (androidId == null ? "unknown" : androidId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.US, "%02X", b));
            return hex.substring(0, 6) + "-" + hex.substring(6, 12) + "-" + hex.substring(12, 18) + "-" + hex.substring(18, 24) + "-" + hex.substring(24, 30);
        } catch (Exception e) {
            return "ANDROID-DEVICE";
        }
    }
}
