package org.dykman.jtl.modules;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.dykman.jtl.SourceInfo;
import org.dykman.jtl.future.AsyncExecutionContext;
import org.dykman.jtl.future.ContextComplete;
import org.dykman.jtl.json.JSON;
import org.dykman.jtl.json.JSONObject;
import org.dykman.jtl.json.JSONValue;

public class LdapModule implements Module {
	boolean debug = false;
	JSONObject baseConfig;

	class LdapWrapper {
		final JSONObject config;
		final String contextKey;
		final String connectionKey;
		LdapWrapper(JSONObject config) {
			this.config = config;
			this.contextKey = "@ldap-context-" + Long.toHexString(System.identityHashCode(this));
			this.connectionKey = "@ldap-connection-" + Long.toHexString(System.identityHashCode(this));
		}
		
		LdapContext getLdapContext(AsyncExecutionContext<JSON> context) throws NamingException {
			final AsyncExecutionContext<JSON> rc = context.getRuntime();
			LdapContext lc = (LdapContext) rc.get(contextKey);
			if(lc == null) {
				synchronized(this) {
					lc = (LdapContext) rc.get(contextKey);
					if(lc == null) {
						
				        Hashtable<String, Object> env = new Hashtable<String, Object>();
				        env.put(Context.SECURITY_AUTHENTICATION, "simple");
				            env.put(Context.SECURITY_PRINCIPAL, stringValue(config.get("username")));
				       
				            env.put(Context.SECURITY_CREDENTIALS, stringValue(config.get("password")));
				        
				        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
				        env.put(Context.PROVIDER_URL, stringValue(config.get("server")));
//				        SearchControls searchControls = new SearchControls();
						lc = new InitialLdapContext(env,null);
						rc.set(contextKey, lc);						
						
						final LdapContext flc = lc;
						rc.onCleanUp(new ContextComplete() {
							
							@Override
							public boolean complete() {
								try {
									flc.close();
									return true;
								} catch (NamingException e) {
									System.err.println("error while releasing LDAP context" + e.getLocalizedMessage());;
									// TODO Auto-generated catch block
									return false;
								}
							}
						});
					}
				}
			}
			return lc;
		}
		
		
	}

	public LdapModule(JSONObject config) {
		this.baseConfig = config;
	}
	@Override
	public void define(SourceInfo meta, AsyncExecutionContext<JSON> parent) {
		// TODO Auto-generated method stub

	}
	protected static String stringValue(JSON j) {
		if (j == null)
			return null;
		switch (j.getType()) {
		case STRING:
		case DOUBLE:
		case LONG:
			return ((JSONValue) j).stringValue();
		default:
			return null;
		}
	}

}