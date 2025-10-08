package quic;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-signed certificate generator for QUIC connections
 * Bu sınıf test amaçlı kullanım içindir, production'da gerçek certificate kullanın
 */
public class SelfSignedCertificateGenerator {
    private static final Logger logger = LoggerFactory.getLogger(SelfSignedCertificateGenerator.class);
    
    public static final SelfSignedCertificate cert;
    
    static {
        try {
            logger.info("Generating self-signed certificate for QUIC...");
            // Multi-host desteği için wildcard certificate veya server hostname
            String hostname = System.getProperty("quic.hostname", "localhost");
            cert = new SelfSignedCertificate(hostname);
            logger.info("Self-signed certificate generated successfully for hostname: {}", hostname);
            logger.debug("Certificate file: {}", cert.certificate().getAbsolutePath());
            logger.debug("Private key file: {}", cert.privateKey().getAbsolutePath());
        } catch (Exception e) {
            logger.error("Failed to generate self-signed certificate", e);
            throw new RuntimeException("Could not generate self-signed certificate", e);
        }
    }
    
    private SelfSignedCertificateGenerator() {
        // Utility class
    }
}