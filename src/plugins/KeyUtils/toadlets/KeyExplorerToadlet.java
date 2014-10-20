/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */


package plugins.KeyUtils.toadlets;

import java.io.IOException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;

import plugins.KeyUtils.Configuration;
import plugins.KeyUtils.GetResult;
import plugins.KeyUtils.KeyExplorerUtils;
import plugins.KeyUtils.KeyUtilsPlugin;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.Metadata;
import freenet.client.MetadataParseException;
import freenet.client.ArchiveManager.ARCHIVE_TYPE;
import freenet.client.async.KeyListenerConstructionException;

import freenet.clients.http.InfoboxNode;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;

import freenet.crypt.HashResult;

import freenet.keys.FreenetURI;
import freenet.keys.BaseClientKey;
import freenet.keys.ClientCHK;

import freenet.l10n.PluginL10n;

import freenet.support.HexUtil;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;
import freenet.support.plugins.helpers1.PluginContext;
import freenet.support.plugins.helpers1.URISanitizer;
import freenet.support.plugins.helpers1.WebInterfaceToadlet;

/**
 * @author saces
 *
 */
public class KeyExplorerToadlet extends WebInterfaceToadlet {
    private static final String PARAM_AUTOMF   = "automf";
    private static final String PARAM_HEXWIDTH = "hexwidth";
    private final PluginL10n    _intl;

    public KeyExplorerToadlet(PluginContext context, PluginL10n intl) {
        super(context, KeyUtilsPlugin.PLUGIN_URI, "");
        _intl = intl;
    }

    public void handleMethodGET(URI uri, HTTPRequest request, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException {
        if ( !normalizePath(request.getPath()).equals("/")) {
            sendErrorPage(ctx, 404, "Not found", "the path '" + uri + "' was not found");

            return;
        }

        String  key;
        String  type;
        boolean automf;
        boolean deep;
        boolean ml;
        int     hexWidth = request.getIntParam(PARAM_HEXWIDTH, Configuration.getHexWidth());

        if (request.isParameterSet(PARAM_AUTOMF)) {
            automf = request.getParam(PARAM_AUTOMF).length() > 0;
        } else {
            automf = Configuration.getAutoMF();
        }

        if (request.isParameterSet(Globals.PARAM_RECURSIVE)) {
            deep = request.getParam(Globals.PARAM_RECURSIVE).length() > 0;
        } else {
            deep = Configuration.getDeep();
        }

        if (request.isParameterSet(Globals.PARAM_MULTILEVEL)) {
            ml = request.getParam(Globals.PARAM_MULTILEVEL).length() > 0;
        } else {
            ml = Configuration.getMultilevel();
        }

        if (request.isParameterSet(Globals.PARAM_URI)) {
            key  = request.getParam(Globals.PARAM_URI);
            type = request.getParam(Globals.PARAM_MFTYPE);
        } else {
            key  = null;
            type = null;
        }

        String extraParams = "&hexwidth=" + hexWidth;

        if (automf) {
            extraParams += "&automf=checked";
        }

        if (deep) {
            extraParams += "&deep=checked";
        }

        if (ml) {
            extraParams += "&ml=checked";
        }

        List<String> errors = new LinkedList<String>();

        if ((hexWidth < 1) || (hexWidth > 1024)) {
            errors.add("Hex display columns out of range. (1-1024). Set to 32 (default).");
            hexWidth = 32;
        }

        try {
            if (Globals.MFTYPE_ZIP.equals(type)) {
                throw new RedirectException(KeyUtilsPlugin.PLUGIN_URI +
                                            "/Site/?mftype=ZIPmanifest&key=" + key + extraParams);
            }

            if (Globals.MFTYPE_TAR.equals(type)) {
                throw new RedirectException(KeyUtilsPlugin.PLUGIN_URI +
                                            "/Site/?mftype=TARmanifest&key=" + key + extraParams);
            }

            if (Globals.MFTYPE_SIMPLE.equals(type)) {
                throw new RedirectException(KeyUtilsPlugin.PLUGIN_URI +
                                            "/Site/?mftype=simplemanifest&key=" + key +
                                            extraParams);
            }

            makeMainPage(ctx, errors, key, hexWidth, automf, deep, ml);
        } catch (URISyntaxException e) {
            this.sendErrorPage(ctx, "Internal Server Error", "Impossible URISyntaxException", e);
        }
    }

    public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx)
            throws ToadletContextClosedException, IOException, RedirectException,
                   URISyntaxException {
        List<String> errors = new LinkedList<String>();

        if ( !isFormPassword(request)) {
            errors.add("Invalid form password");
            makeMainPage(ctx, errors, null, 0, false, false, false);

            return;
        }

        String  key      = request.getPartAsString(Globals.PARAM_URI, 1024);
        int     hexWidth = request.getIntPart(PARAM_HEXWIDTH, 32);
        boolean automf   = request.getPartAsString("automf", 128).length() > 0;
        boolean deep     = request.getPartAsString(Globals.PARAM_RECURSIVE, 128).length() > 0;
        boolean ml       = request.getPartAsString(Globals.PARAM_MULTILEVEL, 128).length() > 0;

        if ((hexWidth < 1) || (hexWidth > 1024)) {
            errors.add("Hex display columns out of range. (1-1024). Set to 32 (default).");
            hexWidth = 32;
        }

        makeMainPage(ctx, errors, key, hexWidth, automf, deep, ml);
    }

    private void makeMainPage(ToadletContext ctx, List<String> errors, String key, int hexWidth,
                              boolean automf, boolean deep, boolean ml)
            throws ToadletContextClosedException, IOException, RedirectException,
                   URISyntaxException {
        PageNode  page        = pluginContext.pageMaker.getPageNode(i18n("KeyExplorer.PageTitle"),
                                    ctx);
        HTMLNode  pageNode    = page.outer;
        HTMLNode  contentNode = page.content;
        byte[]    data        = null;
        GetResult getresult   = null;
        String    extraParams = "&hexwidth=" + hexWidth;

        if (automf) {
            extraParams += "&automf=checked";
        }

        if (deep) {
            extraParams += "&deep=checked";
        }

        if (ml) {
            extraParams += "&ml=checked";
        }

        FreenetURI furi     = null;
        FreenetURI retryUri = null;

        try {
            if ((key != null) && (key.trim().length() > 0)) {
                furi = URISanitizer.sanitizeURI(errors, key, false,
                        URISanitizer.Options.NOMETASTRINGS, URISanitizer.Options.SSKFORUSK);
                retryUri = furi;

                if (ml) {    // multilevel is requestet
                    Metadata tempMD =
                        KeyExplorerUtils.simpleManifestGet(pluginContext.pluginRespirator, furi);
                    FetchResult tempResult =
                        KeyExplorerUtils.splitGet(pluginContext.pluginRespirator, tempMD);

                    getresult = new GetResult(tempResult.asBucket(), true);
                    data      = tempResult.asByteArray();
                } else {    // normal get
                    getresult = KeyExplorerUtils.simpleGet(pluginContext.pluginRespirator, furi);
                    data      = BucketTools.toByteArray(getresult.getData());
                }
            }
        } catch (MalformedURLException e) {
            errors.add("MalformedURL: " + key);
        } catch (IOException e) {
            Logger.error(this, "500", e);
            errors.add("IO Error: " + e.getMessage());
        } catch (MetadataParseException e) {
            errors.add("Metadata Parse Error: " + e.getMessage());
        } catch (FetchException e) {
            errors.add("Get failed (" + e.mode + "): " + e.getMessage());
        } catch (KeyListenerConstructionException e) {
            Logger.error(this, "Hu?", e);
            errors.add("Internal Error: " + e.getMessage());
        } finally {
            if (getresult != null) {
                getresult.free();
            }
        }

        HTMLNode uriBox = createUriBox(pluginContext,
                              ((furi == null) ? null : furi.toString(false, false)), hexWidth,
                              automf, deep, errors);

        if (errors.size() > 0) {
            contentNode.addChild(createErrorBox(errors, path(), retryUri, extraParams));
            errors.clear();
        }

        contentNode.addChild(uriBox);

        if (data != null) {
            Metadata md = null;

            if (getresult.isMetaData()) {
                try {
                    md = Metadata.construct(data);
                } catch (MetadataParseException e) {
                    errors.add("Metadata parse error: " + e.getMessage());
                }

                if (md != null) {
                    if (automf && md.isArchiveManifest()) {
                        if (md.getArchiveType() == ARCHIVE_TYPE.TAR) {
                            writeTemporaryRedirect(ctx, "",
                                                   KeyUtilsPlugin.PLUGIN_URI +
                                                   "/Site/?mftype=TARmanifest&key=" + furi +
                                                   extraParams);

                            return;
                        } else if (md.getArchiveType() == ARCHIVE_TYPE.ZIP) {
                            writeTemporaryRedirect(ctx, "",
                                                   KeyUtilsPlugin.PLUGIN_URI +
                                                   "/Site/?mftype=ZIPmanifest&key=" + furi +
                                                   extraParams);

                            return;
                        } else {
                            errors.add("Unknown Archive Type: " + md.getArchiveType().name());
                        }
                    }

                    if (automf && md.isSimpleManifest()) {
                        writeTemporaryRedirect(ctx, "",
                                               KeyUtilsPlugin.PLUGIN_URI +
                                               "/Site/?mftype=simplemanifest&key=" + furi +
                                               extraParams);

                        return;
                    }
                }
            }

            String title = "Key: " + furi.toString(false, false);

            if (getresult.isMetaData()) {
                title = title + "\u00a0(MetaData)";
            }

            HTMLNode dataBox2 = pluginContext.pageMaker.getInfobox("#", title, contentNode);

            dataBox2.addChild("%", "<pre lang=\"en\" style=\"font-family: monospace;\">\n");
            dataBox2.addChild("#", hexDump(data, hexWidth));
            dataBox2.addChild("%", "\n</pre>");

            if (getresult.isMetaData()) {
                if (md != null) {
                    HTMLNode metaBox = pluginContext.pageMaker.getInfobox("#",
                                           "Decomposed metadata", contentNode);

                    metaBox.addChild("#",
                                     "Metadata version " + Short.toString(md.getParsedVersion()));
                    metaBox.addChild("br");
                    metaBox.addChild("#", "Document type:\u00a0");

                    if (md.isSimpleRedirect()) {
                        metaBox.addChild("#", "SimpleRedirect");
                    } else if (md.isSimpleManifest()) {
                        metaBox.addChild("#", "SimpleManifest");
                    } else if (md.isArchiveInternalRedirect()) {
                        metaBox.addChild("#", "ArchiveInternalRedirect");
                    } else if (md.isArchiveMetadataRedirect()) {
                        metaBox.addChild("#", "ArchiveMetadataRedirect");
                    } else if (md.isArchiveManifest()) {
                        metaBox.addChild("#", "ArchiveManifest");
                    } else if (md.isMultiLevelMetadata()) {
                        metaBox.addChild("#", "MultiLevelMetadata");
                    } else if (md.isSymbolicShortlink()) {
                        metaBox.addChild("#", "SymbolicShortlink");
                    } else {
                        metaBox.addChild("#", "<Unknown document type>");
                    }

                    metaBox.addChild("br");

                    final String MIMEType = md.getMIMEType();

                    if (MIMEType != null) {
                        metaBox.addChild("#", "MIME Type: " + MIMEType);
                        metaBox.addChild("br");
                    }

                    if (md.haveFlags()) {
                        metaBox.addChild("#", "Flags:\u00a0");

                        boolean isFirst = true;

                        if (md.isSplitfile()) {
                            metaBox.addChild("#", "SplitFile");
                            isFirst = false;
                        }

                        if (md.isCompressed()) {
                            if (isFirst) {
                                isFirst = false;
                            } else {
                                metaBox.addChild("#", "\u00a0");
                            }

                            metaBox.addChild("#",
                                             "Compressed (" + md.getCompressionCodec().name + ")");
                        }

                        if (md.hasTopData()) {
                            if (isFirst) {
                                isFirst = false;
                            } else {
                                metaBox.addChild("#", "\u00a0");
                            }

                            metaBox.addChild("#", "HasTopData");
                        }

                        if (isFirst) {
                            metaBox.addChild("#", "<No flag set>");
                        }
                    }

                    metaBox.addChild("br");

                    if (md.isCompressed()) {
                        metaBox.addChild("#",
                                         "Decompressed size: " + md.uncompressedDataLength() +
                                         " bytes.");
                    } else {
                        metaBox.addChild("#", "Uncompressed");
                    }

                    metaBox.addChild("br");

                    if (md.topCompatibilityMode != 0) {
                        metaBox.addChild("#",
                                         "Compatibility mode: " +
                                         md.getTopCompatibilityMode().toString());
                        metaBox.addChild("br");
                    }

                    if (md.hasTopData()) {
                        metaBox.addChild("#", "Top Block Data:");
                        metaBox.addChild("br");
                        metaBox.addChild("#",
                                         "\u00a0\u00a0DontCompress: " +
                                         Boolean.toString(md.topDontCompress));
                        metaBox.addChild("br");
                        metaBox.addChild("#",
                                         "\u00a0\u00a0Compressed size: " +
                                         Long.toString(md.topCompressedSize) + " bytes.");
                        metaBox.addChild("br");
                        metaBox.addChild("#",
                                         "\u00a0\u00a0Decompressed Size: " +
                                         Long.toString(md.topSize) + " bytes.");
                        metaBox.addChild("br");
                        metaBox.addChild("#",
                                         "\u00a0\u00a0Blocks: " +
                                         Integer.toString(md.topBlocksRequired) + " required, " +
                                         Integer.toString(md.topBlocksTotal) + " total.");
                        metaBox.addChild("br");
                    }

                    final HashResult[] hashes = md.getHashes();

                    if ((hashes != null) && (hashes.length > 0)) {
                        metaBox.addChild("#", "Hashes:");
                        metaBox.addChild("br");

                        for (final HashResult hash : hashes) {
                            metaBox.addChild("#",
                                             "\u00a0\u00a0" + hash.type.name() + ": " +
                                             hash.hashAsHex());
                            metaBox.addChild("br");
                        }
                    }

                    if (md.isSplitfile()) {
                        metaBox.addChild("#",
                                         "Splitfile size\u00a0=\u00a0" + md.dataLength() +
                                         " bytes.");
                        metaBox.addChild("br");

                        byte[] splitfileCryptoKey = md.getCustomSplitfileKey();

                        if (splitfileCryptoKey != null) {
                            metaBox.addChild("#",
                                             "Splitfile CryptoKey\u00a0=\u00a0" +
                                             HexUtil.bytesToHex(splitfileCryptoKey));
                            metaBox.addChild("br");
                        }
                    } else {
                        FreenetURI uri = md.getSingleTarget();

                        if ((uri != null) && (md.getParsedVersion() > 0)) {
                            byte[] defaultCryptoKey = null;
                            byte[] uriCryptoKey     = uri.getCryptoKey();

                            try {
                                defaultCryptoKey = md.getCryptoKey(md.getHashes());
                            } catch (IllegalArgumentException ex) {

                                // Ignore
                            }

                            if ((defaultCryptoKey == null) ||
                                    !Arrays.equals(defaultCryptoKey, uriCryptoKey)) {
                                metaBox.addChild(
                                    "#",
                                    "Splitfile CryptoKey (synthesized/guessed)\u00a0=\u00a0" +
                                    HexUtil.bytesToHex(uri.getCryptoKey()));
                                metaBox.addChild("br");
                            }
                        }
                    }

                    metaBox.addChild("#", "Options:");
                    metaBox.addChild("br");

                    if (md.isSimpleManifest()) {
                        metaBox.addChild(new HTMLNode("a", "href",
                                                      KeyUtilsPlugin.PLUGIN_URI +
                                                      "/Site/?mftype=simplemanifest&key=" + furi +
                                                      extraParams, "reopen as manifest"));
                        metaBox.addChild("br");
                    }

                    if (md.isArchiveManifest()) {
                        metaBox.addChild(new HTMLNode("a", "href", KeyUtilsPlugin.PLUGIN_URI +
                                                      "/Site/?mftype=" +
                                                      md.getArchiveType().name() +
                                                      "manifest&key=" + furi +
                                                      extraParams, "reopen as manifest"));
                        metaBox.addChild("br");
                    }

                    if (md.isMultiLevelMetadata()) {
                        if (ml) {
                            metaBox.addChild(new HTMLNode("a", "href", KeyUtilsPlugin.PLUGIN_URI +
                                                          "/?key=" + furi +
                                                          extraParams, "explore multilevel"));
                        } else {
                            metaBox.addChild(new HTMLNode("a", "href", KeyUtilsPlugin.PLUGIN_URI +
                                                          "/?ml=checked&key=" + furi +
                                                          extraParams, "explore multilevel"));
                        }

                        metaBox.addChild("br");
                    }

                    FreenetURI uri = md.getSingleTarget();

                    if (uri != null) {
                        String sfrUri = uri.toString(false, false);

                        metaBox.addChild("#", sfrUri);
                        metaBox.addChild("#", "\u00a0");
                        metaBox.addChild(new HTMLNode("a", "href", "/?key=" + sfrUri, "open"));
                        metaBox.addChild("#", "\u00a0");
                        metaBox.addChild(new HTMLNode("a", "href",
                                                      KeyUtilsPlugin.PLUGIN_URI + "/?key=" +
                                                      sfrUri + extraParams, "explore"));
                    } else {
                        metaBox.addChild(new HTMLNode("a", "href", "/?key=" + furi,
                                                      "reopen normal"));
                    }

                    metaBox.addChild("br");

                    if ((uri == null) && md.isSplitfile()) {
                        metaBox.addChild(new HTMLNode("a", "href",
                                                      KeyUtilsPlugin.PLUGIN_URI + "/Split?key=" +
                                                      furi.toString(false,
                                                          false), "reopen as splitfile"));
                        metaBox.addChild("br");
                        metaBox.addChild(new HTMLNode("a", "href",
                                                      KeyUtilsPlugin.PLUGIN_URI +
                                                      "/Download?action=splitdownload&key=" +
                                                      furi.toString(false,
                                                          false), "split-download"));
                        metaBox.addChild("br");
                    }
                }
            }

            if (errors.size() > 0) {
                contentNode.addChild(createErrorBox(errors));
            }
        }

        contentNode.addChild(Utils.makeDonateFooter(_intl));
        writeHTMLReply(ctx, 200, "OK", pageNode.generate());
    }

    private HTMLNode createUriBox(PluginContext pCtx, String uri, int hexWidth, boolean automf,
                                  boolean deep, List<String> errors) {
        InfoboxNode box           = pCtx.pageMaker.getInfobox("Explore a freenet key");
        HTMLNode    browseBox     = box.outer;
        HTMLNode    browseContent = box.content;

        if ((hexWidth < 1) || (hexWidth > 1024)) {
            errors.add("Hex display columns out of range. (1-1024). Set to 32 (default).");
            hexWidth = 32;
        }

        browseContent.addChild(
            "#", "Display the top level chunk as hexprint or list the content of a manifest");

        HTMLNode browseForm = pCtx.pluginRespirator.addFormChild(browseContent, path(), "uriForm");

        browseForm.addChild("#", "Freenetkey to explore: \u00a0 ");

        if (uri != null) {
            browseForm.addChild("input", new String[] { "type", "name", "size", "value" },
                                new String[] { "text",
                    Globals.PARAM_URI, "70", uri });
        } else {
            browseForm.addChild("input", new String[] { "type", "name", "size" },
                                new String[] { "text",
                    Globals.PARAM_URI, "70" });
        }

        browseForm.addChild("#", "\u00a0");
        browseForm.addChild("input", new String[] { "type", "name", "value" },
                            new String[] { "submit",
                "debug", "Explore!" });
        browseForm.addChild("br");

        if (automf) {
            browseForm.addChild("input", new String[] { "type", "name", "value", "checked" },
                                new String[] { "checkbox",
                    "automf", "ok", "checked" });
        } else {
            browseForm.addChild("input", new String[] { "type", "name", "value" },
                                new String[] { "checkbox",
                    "automf", "ok" });
        }

        browseForm.addChild("#", "\u00a0auto open as manifest if possible\u00a0");

        if (deep) {
            browseForm.addChild("input", new String[] { "type", "name", "value", "checked" },
                                new String[] { "checkbox",
                    Globals.PARAM_RECURSIVE, "ok", "checked" });
        } else {
            browseForm.addChild("input", new String[] { "type", "name", "value" },
                                new String[] { "checkbox",
                    Globals.PARAM_RECURSIVE, "ok" });
        }

        browseForm.addChild(
            "#",
            "\u00a0parse manifest recursive (include multilevel metadata/subcontainers)\u00a0\u00a0");
        browseForm.addChild("#", "Hex display columns:\u00a0");
        browseForm.addChild("input", new String[] { "type", "name", "size", "value" },
                            new String[] { "text",
                PARAM_HEXWIDTH, "3", Integer.toString(hexWidth) });

        return browseBox;
    }

    private String hexDump(byte[] data, int width) {
        StringBuilder sb        = new StringBuilder();
        Formatter     formatter = new Formatter(sb, Locale.US);

        try {
            for (int offset = 0; offset < data.length; offset += width) {
                formatter.format("%07X:", offset);

                for (int i = 0; i < width; i++) {
                    if (i % 2 == 0) {
                        formatter.out().append(' ');
                    }

                    if (i + offset >= data.length) {
                        formatter.out().append("  ");

                        continue;
                    }

                    formatter.format("%02X", data[i + offset]);
                }

                formatter.out().append("  ");

                for (int i = 0; i < width; i++) {
                    if (i + offset >= data.length) {
                        break;
                    }

                    if ((data[i + offset] >= 32) && (data[i + offset] < 127)) {
                        formatter.out().append((char) data[i + offset]);
                    } else {
                        formatter.out().append('.');
                    }
                }

                formatter.out().append('\n');
            }
        } catch (IOException e) {

            // impossible
        }

        formatter.flush();

        return sb.toString();
    }

    private String i18n(String key) {
        return _intl.getBase().getString(key);
    }
}
