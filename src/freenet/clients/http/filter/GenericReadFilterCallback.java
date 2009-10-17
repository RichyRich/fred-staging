/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http.filter;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashSet;

import freenet.clients.http.HTTPRequestImpl;
import freenet.clients.http.StaticToadlet;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLEncoder;
import freenet.support.Logger;
import freenet.support.URIPreEncoder;
import freenet.support.URLDecoder;
import freenet.support.URLEncodedFormatException;
import freenet.support.api.HTTPRequest;

public class GenericReadFilterCallback implements FilterCallback {
	public static final String magicHTTPEscapeString = "_CHECKED_HTTP_";
	public static final HashSet<String> allowedProtocols;
	
	static {
		allowedProtocols = new HashSet<String>();
		allowedProtocols.add("http");
		allowedProtocols.add("https");
		allowedProtocols.add("ftp");
		allowedProtocols.add("mailto");
		allowedProtocols.add("nntp");
		allowedProtocols.add("news");
		allowedProtocols.add("snews");
		allowedProtocols.add("about");
		allowedProtocols.add("irc");
		// file:// ?
	}

	private URI baseURI;
	private URI strippedBaseURI;
	private final FoundURICallback cb;
	
	public GenericReadFilterCallback(URI uri, FoundURICallback cb) {
		this.baseURI = uri;
		this.cb = cb;
		setStrippedURI(uri.toString());
	}
	
	public GenericReadFilterCallback(FreenetURI uri, FoundURICallback cb) {
		try {
			this.baseURI = uri.toRelativeURI();
			setStrippedURI(baseURI.toString());
			this.cb = cb;
		} catch (URISyntaxException e) {
			throw new Error(e);
		}
	}

	private void setStrippedURI(String u) {
		int idx = u.lastIndexOf('/');
		if(idx > 0) {
			u = u.substring(0, idx+1);
			try {
				strippedBaseURI = new URI(u);
			} catch (URISyntaxException e) {
				Logger.error(this, "Can't strip base URI: "+e+" parsing "+u);
				strippedBaseURI = baseURI;
			}
		} else
			strippedBaseURI = baseURI;
	}

	public String processURI(String u, String overrideType) throws CommentException {
		return processURI(u, overrideType, false, false);
	}
	
	// RFC3986
	//  unreserved    = ALPHA / DIGIT / "-" / "." / "_" / "~"
	protected static final String UNRESERVED = "[a-zA-Z0-9\\-\\._~]";
	//  pct-encoded   = "%" HEXDIG HEXDIG
	protected static final String PCT_ENCODED = "%[0-9A-Fa-f][0-9A-Fa-f]";
	//  sub-delims    = "!" / "$" / "&" / "'" / "(" / ")"
	//                / "*" / "+" / "," / ";" / "="
	protected static final String SUB_DELIMS  = "[\\!\\$&'\\(\\)\\*\\+,;=]";
	//  pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
	protected static final String PCHAR      = "(" + UNRESERVED + "|" + PCT_ENCODED + "|" + SUB_DELIMS + "|[:@])";
	//  fragment      = *( pchar / "/" / "?" )
	protected static final String FRAGMENT   = "(" + PCHAR + "|\\/|\\?)*";

	public String processURI(String u, String overrideType, boolean noRelative, boolean inline) throws CommentException {
		if(u.matches("^#" + FRAGMENT + "$")) {
			// Hack for anchors, see #710
			return u;
		}
		
		// evil hack, see #2451 and r24565,r24566
		u = u.replaceAll(" #", " %23");
		
		URI uri;
		URI resolved;
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		try {
			if(logMINOR) Logger.minor(this, "Processing "+u);
			uri = URIPreEncoder.encodeURI(u).normalize();
			if(logMINOR) Logger.minor(this, "Processing "+uri);
			if(!noRelative)
				resolved = baseURI.resolve(uri);
			else
				resolved = uri;
			if(logMINOR) Logger.minor(this, "Resolved: "+resolved);
		} catch (URISyntaxException e1) {
			if(logMINOR) Logger.minor(this, "Failed to parse URI: "+e1);
			throw new CommentException(l10n("couldNotParseURIWithError", "error", e1.getMessage()));
		}
		String path = uri.getPath();
		
		HTTPRequest req = new HTTPRequestImpl(uri, "GET");
		if (path != null){
			if(path.equals("/") && req.isParameterSet("newbookmark")){
				// allow links to the root to add bookmarks
				String bookmark_key = req.getParam("newbookmark");
				String bookmark_desc = req.getParam("desc");
				String bookmark_activelink = req.getParam("hasAnActivelink", "");

				bookmark_key = HTMLEncoder.encode(bookmark_key);
				bookmark_desc = HTMLEncoder.encode(bookmark_desc);

				String url = "/?newbookmark="+bookmark_key+"&desc="+bookmark_desc;
				if (!bookmark_activelink.equals("")) {
					bookmark_activelink = HTMLEncoder.encode(bookmark_activelink);
					url = url+"&hasAnActivelink=true";
				}
				return url;
			} else if(path.startsWith(StaticToadlet.ROOT_URL)) {
				// @see bug #2297
				return path;
			}
		}
		
		String reason = "";
		
		// Try as an absolute URI
		
		URI origURI = uri;
		
		// Convert localhost uri's to relative internal ones.
		
		String host = uri.getHost();
		if(host != null && (host.equals("localhost") || host.equals("127.0.0.1")) && uri.getPort() == 8888) {
			try {
				uri = new URI(null, null, null, -1, uri.getPath(), uri.getQuery(), uri.getFragment());
			} catch (URISyntaxException e) {
				Logger.error(this, "URI "+uri+" looked like localhost but could not parse", e);
				throw new CommentException("URI looked like localhost but could not parse: "+e);
			}
			host = null;
		}
		
		String rpath = uri.getPath();
		
		if(host == null) {
		
			boolean isAbsolute = false;
			
			if(rpath != null) {
				if(logMINOR) Logger.minor(this, "Resolved URI (rpath absolute): "+rpath);
				
				// Valid FreenetURI?
				try {
					String p = rpath;
					while(p.startsWith("/")) {
						isAbsolute = true;
						p = p.substring(1);
					}
					FreenetURI furi = new FreenetURI(p);
					if(logMINOR) Logger.minor(this, "Parsed: "+furi);
					return processURI(furi, uri, overrideType, noRelative || isAbsolute, inline);
				} catch (MalformedURLException e) {
					// Not a FreenetURI
					if(logMINOR) Logger.minor(this, "Malformed URL (a): "+e, e);
					if(e.getMessage() != null) {
						reason = l10n("malformedAbsoluteURL", "error", e.getMessage());
					} else {
						reason = l10n("couldNotParseAbsoluteFreenetURI");
					}
				}
			}
			
			if(!isAbsolute) {
				
				// Relative URI
				
				rpath = resolved.getPath();
				if(rpath == null) throw new CommentException("No URI");
				if(logMINOR) Logger.minor(this, "Resolved URI (rpath relative): "+rpath);
				
				// Valid FreenetURI?
				try {
					String p = rpath;
					while(p.startsWith("/")) p = p.substring(1);
					FreenetURI furi = new FreenetURI(p);
					if(logMINOR) Logger.minor(this, "Parsed: "+furi);
					return processURI(furi, uri, overrideType, noRelative, inline);
				} catch (MalformedURLException e) {
					if(logMINOR) Logger.minor(this, "Malformed URL (b): "+e, e);
					if(e.getMessage() != null) {
						reason = l10n("malformedRelativeURL", "error", e.getMessage());
					} else {
						reason = l10n("couldNotParseRelativeFreenetURI");
					}
				}
				
			}
		
		}
		
		uri = origURI;
		
		if(GenericReadFilterCallback.allowedProtocols.contains(uri.getScheme()))
			return "/?"+GenericReadFilterCallback.magicHTTPEscapeString+ '=' +uri;
		else {
			if(uri.getScheme() == null) {
				throw new CommentException(reason);
			}
			throw new CommentException(l10n("protocolNotEscaped", "protocol", uri.getScheme()));
		}
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("GenericReadFilterCallback."+key, pattern, value);
	}

	private static String l10n(String key) {
		return NodeL10n.getBase().getString("GenericReadFilterCallback."+key);
	}

	private String finishProcess(HTTPRequest req, String overrideType, String path, URI u, boolean noRelative) {
		String typeOverride = req.getParam("type", null);
		if(overrideType != null)
			typeOverride = overrideType;

		if(typeOverride != null) {
			String[] split = HTMLFilter.splitType(typeOverride);
			if(split[1] != null) {
				String charset = split[1];
				if(charset != null) {
					try {
						charset = URLDecoder.decode(charset, false);
					} catch (URLEncodedFormatException e) {
						charset = null;
					}
				}
				if(charset != null && charset.indexOf('&') != -1)
					charset = null;
				if(charset != null && !Charset.isSupported(charset))
					charset = null;
				if(charset != null)
					typeOverride = split[0]+"; charset="+charset;
				else
					typeOverride = split[0];
			}
		}
		
		// REDFLAG any other options we should support? 
		// Obviously we don't want to support ?force= !!
		// At the moment, ?type= and ?force= are the only options supported by FProxy anyway.
		
		try {
			// URI encoding issues: FreenetURI.toString() does URLEncode'ing of critical components.
			// So if we just pass it in to the component-wise constructor, we end up encoding twice, 
			// so get %2520 for a space.
			
			// However, we want to support encoded slashes or @'s in the path, so we don't want to
			// just decode before feeding it to the constructor. It looks like the best option is
			// to construct it ourselves and then re-parse it. This is doing unnecessary work, it
			// would be much easier if we had a component-wise constructor for URI that didn't 
			// re-encode, but at least it works...
			
			StringBuilder sb = new StringBuilder();
			if(strippedBaseURI.getScheme() != null && !noRelative) {
				sb.append(strippedBaseURI.getScheme());
				sb.append("://");
				sb.append(strippedBaseURI.getAuthority());
				assert(path.startsWith("/"));
			}
			sb.append(path);
			if(typeOverride != null) {
				sb.append("?type=");
				sb.append(freenet.support.URLEncoder.encode(typeOverride, "", false, "="));
			}
			if(u.getFragment() != null) {
				sb.append('#');
				sb.append(u.getRawFragment());
			}
			
			URI uri = new URI(sb.toString());
			
			if(!noRelative)
				uri = strippedBaseURI.relativize(uri);
			if(Logger.shouldLog(Logger.MINOR, this))
				Logger.minor(this, "Returning "+uri.toASCIIString()+" from "+path+" from baseURI="+baseURI+" stripped base uri="+strippedBaseURI.toString());
			return uri.toASCIIString();
		} catch (URISyntaxException e) {
			Logger.error(this, "Could not parse own URI: path="+path+", typeOverride="+typeOverride+", frag="+u.getFragment()+" : "+e, e);
			String p = path;
			if(typeOverride != null)
				p += "?type="+typeOverride;
			if(u.getFragment() != null){
				try{
				// FIXME encode it properly
					p += URLEncoder.encode(u.getFragment(),"UTF-8");
				}catch (UnsupportedEncodingException e1){
					throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
				}
			}
			return p;
		}
	}

	private String processURI(FreenetURI furi, URI uri, String overrideType, boolean noRelative, boolean inline) {
		// Valid Freenet URI, allow it
		// Now what about the queries?
		HTTPRequest req = new HTTPRequestImpl(uri, "GET");
		if(cb != null) cb.foundURI(furi);
		if(cb != null) cb.foundURI(furi, inline);
		return finishProcess(req, overrideType, '/' + furi.toString(false, false), uri, noRelative);
	}

	public String onBaseHref(String baseHref) {
		String ret;
		try {
			ret = processURI(baseHref, null, true, false);
		} catch (CommentException e1) {
			Logger.error(this, "Failed to parse base href: "+baseHref+" -> "+e1.getMessage());
			ret = null;
		}
		if(ret == null) {
			Logger.error(this, "onBaseHref() failed: cannot sanitize "+baseHref);
			return null;
		} else {
			try {
				baseURI = new URI(ret);
				setStrippedURI(ret);
			} catch (URISyntaxException e) {
				throw new Error(e); // Impossible
			}
			return baseURI.toASCIIString();
		}
	}

	public void onText(String s, String type) {
		if(cb != null)
			cb.onText(s, type, baseURI);
	}

	static final String PLUGINS_PREFIX = "/plugins/";
	
	/**
	 * Process a form.
	 * Current strategy:
	 * - Both POST and GET forms are allowed to /
	 * Anything that is hazardous should be protected through formPassword.
	 * @throws CommentException If the form element could not be parsed and the user should be told.
	 */
	public String processForm(String method, String action) throws CommentException {
		if(action == null) return null;
		if(method == null) method = "GET";
		method = method.toUpperCase();
		if(!(method.equals("POST") || method.equals("GET"))) 
			return null; // no irregular form sending methods
		// Everything is allowed to / - updating the node, shutting it down, everything.
		// Why? Becuase it's all protected by formPassword anyway.
		// FIXME whitelist? Most things are okay if the user is prompted for a confirmation...
		// FIXME what about /downloads/ /friends/ etc?
		// Allow access to Library for searching, form passwords are used for actions such as adding bookmarks
		if(action.equals("/") || action.equals("/library/"))
			return action;
		try {
			URI uri = URIPreEncoder.encodeURI(action);
			if(uri.getScheme() != null || uri.getHost() != null || uri.getPort() != -1 || uri.getUserInfo() != null)
				throw new CommentException(l10n("invalidFormURI"));
			String path = uri.getPath();
			if(path.startsWith(PLUGINS_PREFIX)) {
				String after = path.substring(PLUGINS_PREFIX.length());
				if(after.indexOf("../") > -1)
					throw new CommentException(l10n("invalidFormURIAttemptToEscape"));
				if(after.matches("[A-Za-z0-9\\.]+"))
					return uri.toASCIIString();
			}
		} catch (URISyntaxException e) {
			throw new CommentException(l10n("couldNotParseFormURIWithError", "error", e.getLocalizedMessage()));
		}
		// Otherwise disallow.
		return null;
	}

	public static String escapeURL(String uri) {
		return "/?" + magicHTTPEscapeString + '=' + uri;
	}
	
}
