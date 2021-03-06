// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io.remotecontrol;

import static org.openstreetmap.josm.tools.I18n.marktr;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Vector;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.preferences.StringProperty;

import sun.security.util.ObjectIdentifier;
import sun.security.x509.AlgorithmId;
import sun.security.x509.BasicConstraintsExtension;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.ExtendedKeyUsageExtension;
import sun.security.x509.GeneralName;
import sun.security.x509.GeneralNameInterface;
import sun.security.x509.GeneralNames;
import sun.security.x509.IPAddressName;
import sun.security.x509.OIDName;
import sun.security.x509.SubjectAlternativeNameExtension;
import sun.security.x509.URIName;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

/**
 * Simple HTTPS server that spawns a {@link RequestProcessor} for every secure connection.
 *
 * @since 6941
 */
public class RemoteControlHttpsServer extends Thread {

    /** The server socket */
    private ServerSocket server;

    private static RemoteControlHttpsServer instance;
    private boolean initOK = false;
    private SSLContext sslContext;

    private static final int HTTPS_PORT = 8112;

    /**
     * JOSM keystore file name.
     * @since 7337
     */
    public static final String KEYSTORE_FILENAME = "josm.keystore";

    /**
     * Preference for keystore password (automatically generated by JOSM).
     * @since 7335
     */
    public static final StringProperty KEYSTORE_PASSWORD = new StringProperty("remotecontrol.https.keystore.password", "");

    /**
     * Preference for certificate password (automatically generated by JOSM).
     * @since 7335
     */
    public static final StringProperty KEYENTRY_PASSWORD = new StringProperty("remotecontrol.https.keyentry.password", "");

    /**
     * Unique alias used to store JOSM localhost entry, both in JOSM keystore and system/browser keystores.
     * @since 7343
     */
    public static final String ENTRY_ALIAS = "josm_localhost";

    /**
     * Creates a GeneralName object from known types.
     * @param t one of 4 known types
     * @param v value
     * @return which one
     * @throws IOException
     */
    private static GeneralName createGeneralName(String t, String v) throws IOException {
        GeneralNameInterface gn;
        switch (t.toLowerCase()) {
            case "uri": gn = new URIName(v); break;
            case "dns": gn = new DNSName(v); break;
            case "ip": gn = new IPAddressName(v); break;
            default: gn = new OIDName(v);
        }
        return new GeneralName(gn);
    }

    /**
     * Create a self-signed X.509 Certificate.
     * @param dn the X.509 Distinguished Name, eg "CN=localhost, OU=JOSM, O=OpenStreetMap"
     * @param pair the KeyPair
     * @param days how many days from now the Certificate is valid for
     * @param algorithm the signing algorithm, eg "SHA256withRSA"
     * @param san SubjectAlternativeName extension (optional)
     */
    private static X509Certificate generateCertificate(String dn, KeyPair pair, int days, String algorithm, String san) throws GeneralSecurityException, IOException {
        PrivateKey privkey = pair.getPrivate();
        X509CertInfo info = new X509CertInfo();
        Date from = new Date();
        Date to = new Date(from.getTime() + days * 86400000l);
        CertificateValidity interval = new CertificateValidity(from, to);
        BigInteger sn = new BigInteger(64, new SecureRandom());
        X500Name owner = new X500Name(dn);

        info.set(X509CertInfo.VALIDITY, interval);
        info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn));

        // Change of behaviour in JDK8:
        // https://bugs.openjdk.java.net/browse/JDK-8040820
        // https://bugs.openjdk.java.net/browse/JDK-7198416
        String version = System.getProperty("java.version");
        if (version == null || version.matches("^(1\\.)?[7].*")) {
            // Java 7 code. To remove with Java 8 migration
            info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
            info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
        } else {
            // Java 8 and later code
            info.set(X509CertInfo.SUBJECT, owner);
            info.set(X509CertInfo.ISSUER, owner);
        }

        info.set(X509CertInfo.KEY, new CertificateX509Key(pair.getPublic()));
        info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algo = new AlgorithmId(AlgorithmId.md5WithRSAEncryption_oid);
        info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo));

        CertificateExtensions ext = new CertificateExtensions();
        // Critical: Not CA, max path len 0
        ext.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(true, false, 0));
        // Critical: only allow TLS ("serverAuth" = 1.3.6.1.5.5.7.3.1)
        ext.set(ExtendedKeyUsageExtension.NAME, new ExtendedKeyUsageExtension(true,
                new Vector<ObjectIdentifier>(Arrays.asList(new ObjectIdentifier("1.3.6.1.5.5.7.3.1")))));

        if (san != null) {
            int colonpos;
            String[] ps = san.split(",");
            GeneralNames gnames = new GeneralNames();
            for(String item: ps) {
                colonpos = item.indexOf(':');
                if (colonpos < 0) {
                    throw new IllegalArgumentException("Illegal item " + item + " in " + san);
                }
                String t = item.substring(0, colonpos);
                String v = item.substring(colonpos+1);
                gnames.add(createGeneralName(t, v));
            }
            // Non critical
            ext.set(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(false, gnames));
        }

        info.set(X509CertInfo.EXTENSIONS, ext);

        // Sign the cert to identify the algorithm that's used.
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privkey, algorithm);

        // Update the algorithm, and resign.
        algo = (AlgorithmId)cert.get(X509CertImpl.SIG_ALG);
        info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algo);
        cert = new X509CertImpl(info);
        cert.sign(privkey, algorithm);
        return cert;
    }

    /**
     * Setup the JOSM internal keystore, used to store HTTPS certificate and private key.
     * @return Path to the (initialized) JOSM keystore
     * @throws IOException if an I/O error occurs
     * @throws GeneralSecurityException if a security error occurs
     * @since 7343
     */
    public static Path setupJosmKeystore() throws IOException, GeneralSecurityException {

        char[] storePassword = KEYSTORE_PASSWORD.get().toCharArray();
        char[] entryPassword = KEYENTRY_PASSWORD.get().toCharArray();

        Path dir = Paths.get(RemoteControl.getRemoteControlDir());
        Path path = dir.resolve(KEYSTORE_FILENAME);
        Files.createDirectories(dir);

        if (!Files.exists(path)) {
            Main.debug("No keystore found, creating a new one");

            // Create new keystore like previous one generated with JDK keytool as follows:
            // keytool -genkeypair -storepass josm_ssl -keypass josm_ssl -alias josm_localhost -dname "CN=localhost, OU=JOSM, O=OpenStreetMap"
            // -ext san=ip:127.0.0.1 -keyalg RSA -validity 1825

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            X509Certificate cert = generateCertificate("CN=localhost, OU=JOSM, O=OpenStreetMap", pair, 1825, "SHA256withRSA",
                    // see #10033#comment:20: All browsers respect "ip" in SAN, except IE which only understands DNS entries:
                    // https://connect.microsoft.com/IE/feedback/details/814744/the-ie-doesnt-trust-a-san-certificate-when-connecting-to-ip-address
                    "dns:localhost,ip:127.0.0.1,dns:127.0.0.1,ip:::1,uri:https://127.0.0.1:"+HTTPS_PORT+",uri:https://::1:"+HTTPS_PORT);

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(null, null);

            // Generate new passwords. See https://stackoverflow.com/a/41156/2257172
            SecureRandom random = new SecureRandom();
            KEYSTORE_PASSWORD.put(new BigInteger(130, random).toString(32));
            KEYENTRY_PASSWORD.put(new BigInteger(130, random).toString(32));

            storePassword = KEYSTORE_PASSWORD.get().toCharArray();
            entryPassword = KEYENTRY_PASSWORD.get().toCharArray();

            ks.setKeyEntry(ENTRY_ALIAS, pair.getPrivate(), entryPassword, new Certificate[]{cert});
            ks.store(Files.newOutputStream(path, StandardOpenOption.CREATE), storePassword);
        }
        return path;
    }

    /**
     * Loads the JOSM keystore.
     * @return the (initialized) JOSM keystore
     * @throws IOException if an I/O error occurs
     * @throws GeneralSecurityException if a security error occurs
     * @since 7343
     */
    public static KeyStore loadJosmKeystore() throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(setupJosmKeystore())) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(in, KEYSTORE_PASSWORD.get().toCharArray());

            if (Main.isDebugEnabled()) {
                for (Enumeration<String> aliases = ks.aliases(); aliases.hasMoreElements();) {
                    Main.debug("Alias in JOSM keystore: "+aliases.nextElement());
                }
            }
            return ks;
        }
    }

    private void initialize() {
        if (!initOK) {
            try {
                KeyStore ks = loadJosmKeystore();

                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, KEYENTRY_PASSWORD.get().toCharArray());

                TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
                tmf.init(ks);

                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

                if (Main.isTraceEnabled()) {
                    Main.trace("SSL Context protocol: " + sslContext.getProtocol());
                    Main.trace("SSL Context provider: " + sslContext.getProvider());
                }

                setupPlatform(ks);

                initOK = true;
            } catch (IOException | GeneralSecurityException e) {
                Main.error(e);
            }
        }
    }

    /**
     * Setup the platform-dependant certificate stuff.
     * @param josmKs The JOSM keystore, containing localhost certificate and private key.
     * @return {@code true} if something has changed as a result of the call (certificate installation, etc.)
     * @throws KeyStoreException if the keystore has not been initialized (loaded)
     * @throws NoSuchAlgorithmException in case of error
     * @throws CertificateException in case of error
     * @throws IOException in case of error
     * @since 7343
     */
    public static boolean setupPlatform(KeyStore josmKs) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        Enumeration<String> aliases = josmKs.aliases();
        if (aliases.hasMoreElements()) {
            return Main.platform.setupHttpsCertificate(ENTRY_ALIAS,
                    new KeyStore.TrustedCertificateEntry(josmKs.getCertificate(aliases.nextElement())));
        }
        return false;
    }

    /**
     * Starts or restarts the HTTPS server
     */
    public static void restartRemoteControlHttpsServer() {
        int port = Main.pref.getInteger("remote.control.https.port", HTTPS_PORT);
        try {
            stopRemoteControlHttpsServer();

            if (RemoteControl.PROP_REMOTECONTROL_HTTPS_ENABLED.get()) {
                instance = new RemoteControlHttpsServer(port);
                if (instance.initOK) {
                    instance.start();
                }
            }
        } catch (BindException ex) {
            Main.warn(marktr("Cannot start remotecontrol https server on port {0}: {1}"),
                    Integer.toString(port), ex.getLocalizedMessage());
        } catch (IOException ioe) {
            Main.error(ioe);
        } catch (NoSuchAlgorithmException e) {
            Main.error(e);
        }
    }

    /**
     * Stops the HTTPS server
     */
    public static void stopRemoteControlHttpsServer() {
        if (instance != null) {
            try {
                instance.stopServer();
                instance = null;
            } catch (IOException ioe) {
                Main.error(ioe);
            }
        }
    }

    /**
     * Constructs a new {@code RemoteControlHttpsServer}.
     * @param port The port this server will listen on
     * @throws IOException when connection errors
     * @throws NoSuchAlgorithmException if the JVM does not support TLS (can not happen)
     */
    public RemoteControlHttpsServer(int port) throws IOException, NoSuchAlgorithmException {
        super("RemoteControl HTTPS Server");
        this.setDaemon(true);

        initialize();

        if (!initOK) {
            Main.error(tr("Unable to initialize Remote Control HTTPS Server"));
            return;
        }

        // Create SSL Server factory
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        if (Main.isTraceEnabled()) {
            Main.trace("SSL factory - Supported Cipher suites: "+Arrays.toString(factory.getSupportedCipherSuites()));
        }

        // Start the server socket with only 1 connection.
        // Also make sure we only listen
        // on the local interface so nobody from the outside can connect!
        // NOTE: On a dual stack machine with old Windows OS this may not listen on both interfaces!
        this.server = factory.createServerSocket(port, 1,
            InetAddress.getByName(Main.pref.get("remote.control.host", "localhost")));

        if (Main.isTraceEnabled() && server instanceof SSLServerSocket) {
            SSLServerSocket sslServer = (SSLServerSocket) server;
            Main.trace("SSL server - Enabled Cipher suites: "+Arrays.toString(sslServer.getEnabledCipherSuites()));
            Main.trace("SSL server - Enabled Protocols: "+Arrays.toString(sslServer.getEnabledProtocols()));
            Main.trace("SSL server - Enable Session Creation: "+sslServer.getEnableSessionCreation());
            Main.trace("SSL server - Need Client Auth: "+sslServer.getNeedClientAuth());
            Main.trace("SSL server - Want Client Auth: "+sslServer.getWantClientAuth());
            Main.trace("SSL server - Use Client Mode: "+sslServer.getUseClientMode());
        }
    }

    /**
     * The main loop, spawns a {@link RequestProcessor} for each connection.
     */
    @Override
    public void run() {
        Main.info(marktr("RemoteControl::Accepting secure connections on port {0}"),
             Integer.toString(server.getLocalPort()));
        while (true) {
            try {
                @SuppressWarnings("resource")
                Socket request = server.accept();
                if (Main.isTraceEnabled() && request instanceof SSLSocket) {
                    SSLSocket sslSocket = (SSLSocket) request;
                    Main.trace("SSL socket - Enabled Cipher suites: "+Arrays.toString(sslSocket.getEnabledCipherSuites()));
                    Main.trace("SSL socket - Enabled Protocols: "+Arrays.toString(sslSocket.getEnabledProtocols()));
                    Main.trace("SSL socket - Enable Session Creation: "+sslSocket.getEnableSessionCreation());
                    Main.trace("SSL socket - Need Client Auth: "+sslSocket.getNeedClientAuth());
                    Main.trace("SSL socket - Want Client Auth: "+sslSocket.getWantClientAuth());
                    Main.trace("SSL socket - Use Client Mode: "+sslSocket.getUseClientMode());
                    Main.trace("SSL socket - Session: "+sslSocket.getSession());
                }
                RequestProcessor.processRequest(request);
            } catch (SocketException se) {
                if (!server.isClosed()) {
                    Main.error(se);
                }
            } catch (IOException ioe) {
                Main.error(ioe);
            }
        }
    }

    /**
     * Stops the HTTPS server.
     *
     * @throws IOException if any I/O error occurs
     */
    public void stopServer() throws IOException {
        if (server != null) {
            server.close();
            Main.info(marktr("RemoteControl::Server (https) stopped."));
        }
    }
}
