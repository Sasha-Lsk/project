package com.apkstudio.app;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 * Подпись APK библиотекой com.android.apksig (apksig.jar — dex-jar) через
 * рефлексию. Схемы v1+v2+v3, чтобы APK ставился на Android 5–14.
 *
 * Ключ: testkey.pk8 (PKCS#8 DER RSA) + testkey.x509.pem (X.509).
 */
public class Signer {

    private final File apksigJar;
    private final File dexOpt;
    private final Toolchain.Log log;

    public Signer(File apksigJar, File dexOptDir, Toolchain.Log log) {
        this.apksigJar = apksigJar;
        this.dexOpt = dexOptDir;
        this.log = log;
    }

    private static PrivateKey loadKey(File pk8) throws Exception {
        byte[] der = readAll(pk8);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // определяем алгоритм по попытке; testkey обычно RSA
        try { return KeyFactory.getInstance("RSA").generatePrivate(spec); }
        catch (Exception e) {
            try { return KeyFactory.getInstance("EC").generatePrivate(spec); }
            catch (Exception e2) { return KeyFactory.getInstance("DSA").generatePrivate(spec); }
        }
    }

    private static X509Certificate loadCert(File pem) throws Exception {
        FileInputStream in = new FileInputStream(pem);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        } finally { in.close(); }
    }

    /** Подписать aligned → signed. Бросает исключение при ошибке. */
    public void sign(File in, File out, File pk8, File pem) throws Exception {
        PrivateKey key = loadKey(pk8);
        X509Certificate cert = loadCert(pem);
        log.line("Ключ: " + key.getAlgorithm() + ", сертификат: " + cert.getSubjectDN());

        DexClassLoader cl = new DexClassLoader(
                apksigJar.getAbsolutePath(), dexOpt.getAbsolutePath(), null,
                Signer.class.getClassLoader());

        Class<?> apkSigner   = cl.loadClass("com.android.apksig.ApkSigner");
        Class<?> apkSignerB  = cl.loadClass("com.android.apksig.ApkSigner$Builder");
        Class<?> signerCfg   = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig");
        Class<?> signerCfgB  = cl.loadClass("com.android.apksig.ApkSigner$SignerConfig$Builder");

        // SignerConfig.Builder(name, PrivateKey, List<X509Certificate>)
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        certs.add(cert);
        Constructor<?> scbCtor = signerCfgB.getConstructor(String.class, PrivateKey.class, List.class);
        Object scb = scbCtor.newInstance("CERT", key, certs);
        Object config = signerCfgB.getMethod("build").invoke(scb);

        List<Object> configs = new ArrayList<Object>();
        configs.add(config);

        // ApkSigner.Builder(List<SignerConfig>)
        Constructor<?> sbCtor = apkSignerB.getConstructor(List.class);
        Object sb = sbCtor.newInstance(configs);

        apkSignerB.getMethod("setInputApk", File.class).invoke(sb, in);
        apkSignerB.getMethod("setOutputApk", File.class).invoke(sb, out);
        callBool(apkSignerB, sb, "setV1SigningEnabled", true);
        callBool(apkSignerB, sb, "setV2SigningEnabled", true);
        callBool(apkSignerB, sb, "setV3SigningEnabled", true);

        Object signer = apkSignerB.getMethod("build").invoke(sb);
        Method signMethod = apkSigner.getMethod("sign");
        signMethod.invoke(signer);
    }

    private static void callBool(Class<?> c, Object o, String m, boolean v) {
        try { c.getMethod(m, boolean.class).invoke(o, v); }
        catch (Throwable ignore) {}
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
