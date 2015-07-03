package org.dykman.jtl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.dykman.jtl.jtlLexer;
import org.dykman.jtl.jtlParser;
import org.dykman.jtl.json.JSON;
import org.dykman.jtl.json.JSONBuilder;
import org.dykman.jtl.json.JSONObject;
import org.dykman.jtl.json.JSON.JSONType;
import org.dykman.jtl.jtlParser.JtlContext;
import org.dykman.jtl.future.AsyncExecutionContext;
import org.dykman.jtl.future.InstructionFuture;
import org.dykman.jtl.future.InstructionFutureFactory;
import org.dykman.jtl.future.InstructionFutureValue;
import org.dykman.jtl.future.InstructionFutureVisitor;
import org.dykman.jtl.future.SimpleExecutionContext;

import com.google.common.util.concurrent.ListeningExecutorService;

public class JtlCompiler {
	final JSONBuilder jsonBuilder;
	boolean trace;
	boolean profile;
	boolean imported;
/*
	public JtlCompiler(JSONBuilder jsonBuilder) {
		this(jsonBuilder,false,false,false);
	}
	
	*/
	public JtlCompiler(JSONBuilder jsonBuilder,boolean trace, boolean profile,boolean imported) {
		this.jsonBuilder = jsonBuilder;
		this.trace= trace;
		this.profile = profile;
		this.imported = imported;
	}
	
	public InstructionFuture<JSON> parse(InputStream in) 
		throws IOException {
		return parse(in, trace, profile);
	}
	
	public InstructionFuture<JSON> parse(InputStream in,boolean trace,boolean profile) 
			throws IOException {
			return parse( new jtlLexer(new ANTLRInputStream(in)),trace,profile);
		}
	
	public InstructionFuture<JSON> parse(File in,boolean trace,boolean profile) 
			throws IOException {
		return parse(new FileInputStream(in),trace,profile);
		}
	public InstructionFuture<JSON> parse(File in) 
		throws IOException {
		return parse(in, trace, profile);
	}

	public InstructionFuture<JSON> parse(String in) 
		throws IOException {
		return parse(in, trace, profile);
	}
	
	public InstructionFuture<JSON> parse(String in,boolean trace,boolean profile) 
			throws IOException {
			return parse( new jtlLexer(new ANTLRInputStream(in)),trace,profile);
		}
	protected InstructionFuture<JSON> parse(jtlLexer lexer) 
		throws IOException {
		return parse(lexer, trace, profile);
	}
	
	protected InstructionFuture<JSON> parse(jtlLexer lexer,boolean trace,boolean profile) 
			throws IOException {
	//	lexer.
			jtlParser parser = new jtlParser(new CommonTokenStream(lexer));
			parser.setTrace(trace);
			parser.setProfile(profile);
//			parser.getCurrentToken();
			JtlContext tree = parser.jtl();
			InstructionFutureVisitor visitor = new InstructionFutureVisitor(jsonBuilder,imported);
			InstructionFutureValue<JSON> v = visitor.visit(tree);
			return v.inst;
		}

	public static AsyncExecutionContext<JSON> createInitialContext(
			JSON data,
			JSON config,
			InstructionFutureFactory factory,
			ListeningExecutorService les ) {

		SimpleExecutionContext context = new SimpleExecutionContext(factory.builder(),data,config);
		context.setExecutionService(les);
		
		// configurable: import, extend
		if(config.getType() == JSONType.OBJECT) {
			JSONObject conf = (JSONObject) config;
			JSONObject modules= (JSONObject)conf.get("modules");
			context.define("module", factory.loadModule(modules));
			context.define("import", factory.importInstruction(config));
		}
		
		context.define("error", factory.defaultError());
		context.define("params", factory.params());

		// external data
		context.define("file", factory.file());
		context.define("url", factory.url());

		// list-oriented
		context.define("unique", factory.unique());
		context.define("count", factory.count());
		context.define("sort", factory.sort(false));
		context.define("rsort", factory.sort(true));
		context.define("filter", factory.filter());

		// object-oriented
		context.define("group", factory.groupBy());
		context.define("map", factory.map());
		context.define("collate", factory.collate());

		
		context.define("contains", factory.contains());
		context.define("omap", factory.omap());
	
		// boolean type test only
		context.define("null", factory.isNull());
		context.define("object", factory.isObject());

		// with 0 args, they return boolean type test
		// with 1 arg, attempts to coerce to the specified type
		context.define("array", factory.isArray());
		context.define("number", factory.isNumber());
		context.define("string", factory.isString());
		context.define("boolean", factory.isBoolean());

		
		return context;
	}

}
