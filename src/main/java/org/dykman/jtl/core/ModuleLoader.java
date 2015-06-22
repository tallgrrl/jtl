package org.dykman.jtl.core;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;

import org.dykman.jtl.core.engine.future.AsyncExecutionContext;

public class ModuleLoader {

	JSONBuilder builder;
	JSONObject modules;
	
	public ModuleLoader(JSONBuilder builder,JSONObject conf) {
		this.builder = builder;
		this.modules = conf;
		File mods = new File("modules.json");
		if(mods.exists()) try {
			modules = (JSONObject) builder.parse(mods);
		} catch (IOException e) {
			throw new RuntimeException("while loading module config",e);
		}
	}

	private static ModuleLoader theInstance = null;
	public static ModuleLoader getInstance(JSONBuilder builder, JSONObject config) {
		if(theInstance == null) {
			synchronized (ModuleLoader.class) {
				if(theInstance == null) {
					theInstance = new ModuleLoader(builder,config);
				}				
			}
		}
		return theInstance;
	}
	protected String stringValue(JSON j) {
		if(j == null) return null;
		switch(j.getType()) {
			case STRING:
			case DOUBLE:
			case LONG:
				return ((JSONValue)j).stringValue();
			default:
				return null;
		}
	}
	
	public int create(
		String name,
		AsyncExecutionContext<JSON> context,
		JSONObject config) {
//		JSONObject m = (JSONObject)modules.get(name);
//		if(m == null) throw new RuntimeException("can not find module " + name);
		try {
			if(modules == null) {
				System.err.println("no modules are defined");
			  throw new RuntimeException("no modules are defined");
			} 
			JSONObject mod = (JSONObject)modules.get(name);
			if(mod == null) {
				System.err.println("module " + name + " is not defined");
			  throw new RuntimeException("module " + name + " is not defined");
			} 
			String klass = stringValue(mod.get("class"));
			Class kl = Class.forName(klass);
			Constructor<Module> mc = kl.getConstructor(config.getClass());
			Module o = mc.newInstance(config);
//			AsyncExecutionContext<JSON> mc = context.createChild(false);
			o.define(context);
			return 1;
		} catch(Exception e) {
			System.err.println("error loading module " + name + ": " + e.getLocalizedMessage());
			  throw new RuntimeException("error loading module " + name,e);
		}
		
	}
}