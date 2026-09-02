package io.kbrag.parser.security;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.util.ZipSecureFile;

/**
 * XXE and decompression hardening for the OOXML (docx/xlsx) parsing path, requirement doc §4.2.
 *
 * <p>The Python service calls {@code defusedxml.defuse_stdlib()} once at startup to patch the stdlib
 * XML modules process-wide. The JVM has no equivalent global switch - every XML parser comes from a
 * factory its caller configures - so the posture here is reached by audit plus one explicit setting,
 * and the audit is the part worth recording:
 *
 * <ul>
 *   <li>POI builds every parser it uses through {@code org.apache.poi.util.XMLHelper}, which sets
 *       {@code disallow-doctype-decl}, disables external general/parameter entities and external DTD
 *       loading, and turns on {@code FEATURE_SECURE_PROCESSING}. A DTD cannot be declared, so an
 *       external entity cannot be declared either - which is what actually blocks XXE. No
 *       monkeypatching of the library is required as long as the pinned version keeps those defaults.
 *   <li>This service parses no XML of its own: the docx relationship lookup goes through POI's
 *       {@code PackagePart} API rather than a hand-rolled DOM parse, precisely so there is no second
 *       parser to keep hardened.
 *   <li>No HTTP client is implemented anywhere in this service, which removes the classic
 *       XXE-to-SSRF exfiltration path even if an external entity reference were ever resolved.
 * </ul>
 *
 * <p>What is set here is POI's inflation-ratio floor - the second line of defence behind
 * {@link ZipSafetyGuard}, which checks the sizes an archive <i>declares</i>. This one watches what an
 * entry actually inflates to while it is being read, so a package that lies in its central directory
 * still gets stopped.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
public final class XmlHardening {

    /**
     * Refuse a zip entry that expands to more than 100x its stored size.
     *
     * <p>POI's own default is 0.01 (the same ratio); it is set explicitly so the guarantee survives a
     * future POI default change, and so this file is the one place a reader has to look.
     */
    private static final double MIN_INFLATE_RATIO = 0.01d;

    private XmlHardening() {
    }

    /**
     * Applies the process-wide OOXML hardening. Called once at startup, before any request can trigger
     * XML parsing.
     */
    public static void harden() {
        ZipSecureFile.setMinInflateRatio(MIN_INFLATE_RATIO);
        log.info("ooxml parsing hardened, minInflateRatio={}, xxeGuard=poi_xmlhelper_defaults",
                MIN_INFLATE_RATIO);
    }
}
