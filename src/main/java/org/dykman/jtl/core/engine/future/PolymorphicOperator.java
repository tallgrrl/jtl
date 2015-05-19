package org.dykman.jtl.core.engine.future;

import org.dykman.jtl.core.JSON;
import org.dykman.jtl.core.JSONArray;
import org.dykman.jtl.core.JSONObject;
import org.dykman.jtl.core.engine.ExecutionException;

public interface PolymorphicOperator {

	public JSON op(AsyncExecutionContext<JSON> context, JSON l, JSON r)
		throws ExecutionException;
	
	public JSON op(AsyncExecutionContext<JSON> context, JSONObject l, JSONObject r);
	public JSON op(AsyncExecutionContext<JSON> context, JSONArray l, JSONArray r);
	
	public Boolean op(AsyncExecutionContext<JSON> context, Boolean l, Boolean r);
	public Long op(AsyncExecutionContext<JSON> context, Long l, Long r);
	public Double op(AsyncExecutionContext<JSON> context, Double l, Double r);
	public String op(AsyncExecutionContext<JSON> context, String l, String r);

	public JSON op(AsyncExecutionContext<JSON> context, JSONObject l, JSONArray r);
	public JSON op(AsyncExecutionContext<JSON> context, JSONArray l, JSONObject r);

	public JSON op(AsyncExecutionContext<JSON> context, JSONObject l, JSON r);
	public JSON op(AsyncExecutionContext<JSON> context, JSONArray l, JSON r);
	public JSON op(AsyncExecutionContext<JSON> context, Boolean l, JSON r);
	public JSON op(AsyncExecutionContext<JSON> context, Long l, JSON r);
	public JSON op(AsyncExecutionContext<JSON> context, Double l, JSON r);
	public JSON op(AsyncExecutionContext<JSON> context, String l, JSON r);


}