package com.apktool.app;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

/** Чтение ключа PKCS#8 (.pk8) и сертификата X.509 (.pem) для подписи APK. */
public class ApkSignHelper {

    public static PrivateKey readPk8(java.io.File f) throws Exception {
        byte[] der = readAll(new FileInputStream(f));
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // Тип ключа определяем автоматически (RSA/EC).
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    public static X509Certificate readCert(java.io.File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
