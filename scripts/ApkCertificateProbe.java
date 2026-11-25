import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;

class ApkCertificateProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one APK");
        var result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK signature verification failed");
        var fingerprints = new ArrayList<String>();
        for (var certificate : result.getSignerCertificates()) {
            fingerprints.add(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        }
        if (fingerprints.isEmpty()) throw new IllegalStateException("No verified signer certificates");
        Collections.sort(fingerprints);
        fingerprints.forEach(System.out::println);
    }
}
