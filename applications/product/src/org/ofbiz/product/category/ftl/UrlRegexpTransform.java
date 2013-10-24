/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.product.category.ftl;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.Perl5Matcher;
import org.ofbiz.base.util.Debug;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.product.category.UrlRegexpConfigUtil;
import org.ofbiz.webapp.control.RequestHandler;

import freemarker.core.Environment;
import freemarker.ext.beans.BeanModel;
import freemarker.template.SimpleScalar;
import freemarker.template.TemplateScalarModel;
import freemarker.template.TemplateTransformModel;

/**
 * UrlRegexpTransform - Freemarker Transform for Products URLs (links)
 * 
 */
public class UrlRegexpTransform implements TemplateTransformModel {

    private static final String module = UrlRegexpTransform.class.getName();

    public boolean checkArg(Map args, String key, boolean defaultValue) {
        if (!args.containsKey(key)) {
            return defaultValue;
        } else {
            Object o = args.get(key);
            if (o instanceof SimpleScalar) {
                SimpleScalar s = (SimpleScalar) o;
                return "true".equalsIgnoreCase(s.getAsString());
            }
            return defaultValue;
        }
    }

    public Writer getWriter(final Writer out, Map args) {
        final StringBuffer buf = new StringBuffer();
        final boolean fullPath = checkArg(args, "fullPath", false);
        final boolean secure = checkArg(args, "secure", false);
        final boolean encode = checkArg(args, "encode", true);

        return new Writer(out) {

            public void write(char cbuf[], int off, int len) {
                buf.append(cbuf, off, len);
            }

            public void flush() throws IOException {
                out.flush();
            }

            public void close() throws IOException {
                try {
                    Environment env = Environment.getCurrentEnvironment();
                    BeanModel req = (BeanModel) env.getVariable("request");
                    BeanModel res = (BeanModel) env.getVariable("response");
                    Object prefix = env.getVariable("urlPrefix");
                    if (req != null) {
                        HttpServletRequest request = (HttpServletRequest) req.getWrappedObject();
                        ServletContext ctx = (ServletContext) request.getAttribute("servletContext");
                        HttpServletResponse response = null;
                        if (res != null) {
                            response = (HttpServletResponse) res.getWrappedObject();
                        }
                        HttpSession session = request.getSession();
                        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");

                        // anonymous shoppers are not logged in
                        if (userLogin != null && "anonymous".equals(userLogin.getString("userLoginId"))) {
                            userLogin = null;
                        }

                        RequestHandler rh = (RequestHandler) ctx.getAttribute("_REQUEST_HANDLER_");
                        out.write(seoUrl(rh.makeLink(request, response, buf.toString(), fullPath, secure, encode), userLogin == null));
                    } else if (prefix != null) {
                        if (prefix instanceof TemplateScalarModel) {
                            TemplateScalarModel s = (TemplateScalarModel) prefix;
                            String prefixString = s.getAsString();
                            String bufString = buf.toString();
                            boolean prefixSlash = prefixString.endsWith("/");
                            boolean bufSlash = bufString.startsWith("/");
                            if (prefixSlash && bufSlash) {
                                bufString = bufString.substring(1);
                            } else if (!prefixSlash && !bufSlash) {
                                bufString = "/" + bufString;
                            }
                            out.write(prefixString + bufString);
                        }
                    } else {
                        out.write(buf.toString());
                    }
                } catch (Exception e) {
                    throw new IOException(e.getMessage());
                }
            }
        };
    }

    /**
     * Transform a url according to seo pattern regular expressions.
     * 
     * @param url
     *            , String to do the seo transform
     * @param isAnon
     *            , boolean to indicate whether it's an anonymous visit.
     * 
     * @return String, the transformed url.
     */
    public static String seoUrl(String url, boolean isAnon) {
        Perl5Matcher matcher = new Perl5Matcher();
        if (UrlRegexpConfigUtil.checkUseUrlRegexp() && matcher.matches(url, UrlRegexpConfigUtil.getGeneralRegexpPattern())) {
            Iterator<String> keys = UrlRegexpConfigUtil.getSeoPatterns().keySet().iterator();
            boolean foundMatch = false;
            while (keys.hasNext()) {
                String key = keys.next();
                Pattern pattern = UrlRegexpConfigUtil.getSeoPatterns().get(key);
                if (pattern.getPattern().contains(";jsessionid=")) {
                    if (isAnon) {
                        if (UrlRegexpConfigUtil.isJSessionIdAnonEnabled()) {
                            continue;
                        }
                    } else {
                        if (UrlRegexpConfigUtil.isJSessionIdUserEnabled()) {
                            continue;
                        } else {
                            boolean foundException = false;
                            for (int i = 0; i < UrlRegexpConfigUtil.getUserExceptionPatterns().size(); i++) {
                                if (matcher.matches(url, UrlRegexpConfigUtil.getUserExceptionPatterns().get(i))) {
                                    foundException = true;
                                    break;
                                }
                            }
                            if (foundException) {
                                continue;
                            }
                        }
                    }
                }
                String replacement = UrlRegexpConfigUtil.getSeoReplacements().get(key);
                if (matcher.matches(url, pattern)) {
                    for (int i = 1; i < matcher.getMatch().groups(); i++) {
                        replacement = replacement.replaceAll("\\$" + i, matcher.getMatch().group(i));
                    }
                    // break if found any matcher
                    url = replacement;
                    foundMatch = true;
                    break;
                }
            }
            if (!foundMatch && UrlRegexpConfigUtil.isDebugEnabled()) {
                Debug.logInfo("Can NOT find a seo transform pattern for this url: " + url, module);
            }
        }
        return url;
    }

    static {
        UrlRegexpConfigUtil.init();
    }

    /**
     * Forward a uri according to forward pattern regular expressions. Note: this is developed for Filter usage.
     * 
     * @param uri
     *            String to reverse transform
     * @return String
     */
    public static boolean forwardUri(HttpServletResponse response, String uri) {
        Perl5Matcher matcher = new Perl5Matcher();
        boolean foundMatch = false;
        Integer responseCodeInt = null;
        if (UrlRegexpConfigUtil.checkUseUrlRegexp() && UrlRegexpConfigUtil.getForwardPatterns() != null && UrlRegexpConfigUtil.getForwardReplacements() != null) {
            Iterator<String> keys = UrlRegexpConfigUtil.getForwardPatterns().keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                Pattern pattern = UrlRegexpConfigUtil.getForwardPatterns().get(key);
                String replacement = UrlRegexpConfigUtil.getForwardReplacements().get(key);
                if (matcher.matches(uri, pattern)) {
                    for (int i = 1; i < matcher.getMatch().groups(); i++) {
                        replacement = replacement.replaceAll("\\$" + i, matcher.getMatch().group(i));
                    }
                    // break if found any matcher
                    uri = replacement;
                    responseCodeInt = UrlRegexpConfigUtil.getForwardResponseCodes().get(key);
                    foundMatch = true;
                    break;
                }
            }
        }
        if (foundMatch) {
            if (responseCodeInt == null) {
                response.setStatus(UrlRegexpConfigUtil.DEFAULT_RESPONSECODE);
            } else {
                response.setStatus(responseCodeInt.intValue());
            }
            response.setHeader("Location", uri);
        } else if (UrlRegexpConfigUtil.isDebugEnabled()) {
            Debug.logInfo("Can NOT forward this url: " + uri, module);
        }
        return foundMatch;
    }
}