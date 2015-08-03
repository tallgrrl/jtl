package org.dykman.jtl.future;

import static com.google.common.util.concurrent.Futures.immediateFailedCheckedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.dykman.jtl.ExecutionException;
import org.dykman.jtl.SourceInfo;
import org.dykman.jtl.json.JSON;
import org.dykman.jtl.json.JSONBuilder;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

public class SimpleExecutionContext implements AsyncExecutionContext<JSON> {

	final AsyncExecutionContext<JSON> parent;
	final boolean functionContext;
	final Map<String, InstructionFuture<JSON>> functions = new ConcurrentHashMap<>();
	ListeningExecutorService executorService = null;
	JSON conf;
	ListenableFuture<JSON> data;

	File currentDirectory = new File(".");
	JSONBuilder builder = null;
	
	boolean debug = false;
	final Map<String, AsyncExecutionContext<JSON>> namedContexts = new ConcurrentHashMap<>();

	public SimpleExecutionContext(AsyncExecutionContext<JSON> parent, JSONBuilder builder ,
		ListenableFuture<JSON> data,JSON conf, File f, boolean fc,boolean debug) {
		this.parent = parent;
		this.functionContext = fc;
		this.builder = builder;
		this.conf = conf;
		this.data = data;
		this.currentDirectory = f;
		this.debug = debug;
//		if(fc && data!=null) define("_",InstructionFutureFactory.value(data));
	}
	public SimpleExecutionContext(JSONBuilder builder,ListenableFuture<JSON> data,JSON conf,File f) {
		this(null,builder, data,conf, f,false,false);
	}

	public ListenableFuture<JSON> dataContext() {
		return data;
	}
	public ListenableFuture<JSON> config() {
		if(conf==null && parent!=null) return parent.config();
		return immediateFuture(conf);
	}

	@Override
	public JSONBuilder builder() {
		JSONBuilder r = this.builder;
		AsyncExecutionContext<JSON> p = this.getParent();
		while(r  == null && p != null) {
			r = p.builder();
			p = p.getParent();
		}
		return r;
		
	}
	Map<String, AtomicInteger> counterMap = Collections.synchronizedMap(new HashMap<>());
	
	@Override 
	public synchronized int counter(String label,int interval) {
	   AtomicInteger ai = counterMap.get(label);
	   if(ai == null) {
	      ai = new AtomicInteger();
	      counterMap.put(label,ai);
	   }
	   return ai.addAndGet(interval);
	}
	@Override
	public boolean debug() {
	   return debug;
	}
   @Override
   public boolean debug(boolean d) {
      return debug = d;
   }
	@Override
	public AsyncExecutionContext<JSON> getParent() {
		return parent;
	}
	@Override
	public AsyncExecutionContext<JSON> getMasterContext() {
		AsyncExecutionContext<JSON> c= this;
		AsyncExecutionContext<JSON> parent = c.getParent();
		while(parent!=null) {
			c= parent;
			parent = c.getParent();
		}
		return c;
	}
	@Override
	public AsyncExecutionContext<JSON> getNamedContext(String label) {
		return getNamedContext(label, false,null);
	}

	public AsyncExecutionContext<JSON> getNamedContext(String label,
			boolean create,SourceInfo info) {
		AsyncExecutionContext<JSON> c = namedContexts.get(label);
		if (c == null) {
			synchronized (this) {
				c = namedContexts.get(label);
				if (c == null) {
					if (parent != null) {
						c = parent.getNamedContext(label, false,info);
						if (c != null)
							return c;
					}
					if (create) {
						c = this.createChild(false,null,info);
						namedContexts.put(label, c);
					}
				}
			}
		}
		return c;
	}

	@Override
	public void define(String n, InstructionFuture<JSON> i) {
		functions.put(n, i);
	}


	@Override
	public AsyncExecutionContext<JSON> createChild(boolean fc,ListenableFuture<JSON> data,SourceInfo source) {
	     AsyncExecutionContext<JSON>  r = new SimpleExecutionContext(this,null,data,null,currentDirectory(), fc,debug);
 //       if(fc && data!=null) r.define("_",InstructionFutureFactory.value(data,source));
        return r;
	}


	@Override
	public InstructionFuture<JSON> getdef(String name) {
		InstructionFuture<JSON> r = null;
		String[] parts = name.split("[.]",2);
		if (parts.length > 1) {
			AsyncExecutionContext<JSON> named = getNamedContext(parts[0]);
			if (named != null) {
				return named.getdef(parts[1]);
			}

		} else {
			r = functions.get(name);
			if (parent != null && r == null
					&& !(functionContext && Character.isDigit(name.charAt(0)))) {
				r = parent.getdef(name);

			}
		}
		return r;
	}

	@Override
	public ListenableFuture<JSON> lookup(String name, ListenableFuture<JSON> t)
			throws ExecutionException {
		InstructionFuture<JSON> inst = getdef(name);
		if (inst != null) {
			return inst.call(this, t);
		}
		// System.err.println("lookup failed: " + name);
		return immediateFailedCheckedFuture(new ExecutionException(
				"lookup failed: " + name));
	}

	public void setExecutionService(ListeningExecutorService s) {
		executorService = s;
	}


	@Override
	public ListeningExecutorService executor() {
		if (executorService != null)
			return executorService;
		if (parent != null)
			return parent.executor();
		return null;
	}

	@Override
	public File currentDirectory() {
		return currentDirectory;
	}
	public File file(String f) {
		return new File(currentDirectory,f);
	}

}
