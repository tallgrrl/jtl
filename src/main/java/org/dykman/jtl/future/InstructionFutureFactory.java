package org.dykman.jtl.future;

import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.immediateCheckedFuture;
import static com.google.common.util.concurrent.Futures.immediateFailedCheckedFuture;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.transform;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.tree.TerminalNode;
import org.dykman.jtl.ExecutionException;
import org.dykman.jtl.JtlCompiler;
import org.dykman.jtl.Pair;
import org.dykman.jtl.json.Frame;
import org.dykman.jtl.json.JSON;
import org.dykman.jtl.json.JSONArray;
import org.dykman.jtl.json.JSONBuilder;
import org.dykman.jtl.json.JSONObject;
import org.dykman.jtl.json.JSONValue;
import org.dykman.jtl.json.JSON.JSONType;
import org.dykman.jtl.modules.ModuleLoader;

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class InstructionFutureFactory {

	final JSONBuilder builder;

	protected static final String JTL_INTERNAL = "_jtl_internal_";
	protected static final String JTL_INTERNAL_KEY = JTL_INTERNAL + "key_";

	public InstructionFutureFactory(JSONBuilder builder) {
		this.builder = builder;
	}

	public static InstructionFuture<JSON> memo(
			final InstructionFuture<JSON> inst) {
		return new AbstractInstructionFuture() {
			private ListenableFuture<JSON> result = null;
			private boolean fired = false;

			@Override
			public InstructionFuture<JSON> unwrap(AsyncExecutionContext<JSON> context) {
				return inst.unwrap(context);
			}

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				if (!fired) {
					synchronized (this) {
						if (!fired) {
							result = inst.call(context, data);
							fired = true;
						}
					}
				}
				return result;
			}
		};
	}

	public JSONBuilder builder() {
		return builder;
	}

	public InstructionFuture<JSON> file() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> f = context.getdef("1");
				return transform(f.call(context, data),
						new AsyncFunction<JSON, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(JSON input)
									throws Exception {
								Callable<JSON> cc = new Callable<JSON>() {

									@Override
									public JSON call() throws Exception {
										File ff = new File(stringValue(input));
										if (ff.exists())
											return builder.parse(ff);
										System.err
												.println("failed to find file "
														+ ff.getPath());
										return builder.value();
									}
								};
								return context.executor().submit(cc);
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> map() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> gbe = context.getdef("1");
				
				final InstructionFuture<JSON> mapfunc;
				if (gbe != null) {
					mapfunc = gbe.unwrap(context);
				} else {
					return immediateCheckedFuture(builder.value());
				}

				return transform(data, new AsyncFunction<JSON, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						switch (input.getType()) {
						case OBJECT: {
							JSONObject obj = (JSONObject) input;
							List<ListenableFuture<Pair<String, JSON>>> ll = new ArrayList<>();

							for (Pair<String, JSON> pp : obj) {
								AsyncExecutionContext<JSON> ctx = context
										.createChild(true);
								ctx.define("0", value("map"));
								ctx.define(JTL_INTERNAL_KEY, value(pp.f));
								ctx.define("key", value(pp.f));
								final String kk = pp.f;
								ListenableFuture<JSON> remapped = mapfunc.call(
										context, immediateCheckedFuture(pp.s));
								ll.add(transform(
										remapped,
										new KeyedAsyncFunction<JSON, Pair<String, JSON>, String>(
												kk) {
											@Override
											public ListenableFuture<Pair<String, JSON>> apply(
													JSON input)
													throws Exception {
												return immediateCheckedFuture(new Pair<>(
														k, input));
											}
										}));

							}
							return transform(
									allAsList(ll),
									new AsyncFunction<List<Pair<String, JSON>>, JSON>() {

										@Override
										public ListenableFuture<JSON> apply(
												List<Pair<String, JSON>> input)
												throws Exception {
											JSONObject result = builder
													.object(null);
											for (Pair<String, JSON> pp : input) {
												result.put(pp.f, pp.s);
											}

											return immediateCheckedFuture(result);
										}
									});
						}
						case ARRAY:
						default:
							return immediateCheckedFuture(builder.value());
						}
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> groupBy() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> gbe = context.getdef("1");
				if (gbe != null) {
					gbe = gbe.unwrap(context);
				} else {
					return data;
				}

				final InstructionFuture<JSON> filter = gbe;
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						// JSONObject obj = builder.object(null);
						JSONType type = input.getType();
						if (type != JSONType.ARRAY && type != JSONType.FRAME)
							return immediateCheckedFuture(builder.object(null));
						JSONArray array = (JSONArray) input;
						List<ListenableFuture<Pair<JSON, JSON>>> ll = new ArrayList<>();
						for (JSON j : array) {
							final JSON k = j;
							ll.add(transform(
									filter.call(context,
											immediateCheckedFuture(k)),
									new AsyncFunction<JSON, Pair<JSON, JSON>>() {
										public ListenableFuture<Pair<JSON, JSON>> apply(
												JSON inp) throws Exception {
											return immediateCheckedFuture(new Pair<>(
													k, inp));
										}
									}));

						}
						return transform(
								allAsList(ll),
								new AsyncFunction<List<Pair<JSON, JSON>>, JSON>() {

									@Override
									public ListenableFuture<JSON> apply(
											List<Pair<JSON, JSON>> input)
											throws Exception {
										JSONObject obj = builder.object(null);
										for (Pair<JSON, JSON> pp : input) {
											String s = stringValue(pp.s);
											JSONArray a = (JSONArray) obj
													.get(s);
											if (a == null) {
												a = builder.array(obj);
												obj.put(s, a, true);
											}
											a.add(pp.f);
										}
										return immediateCheckedFuture(obj);
									}
								});
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> importInstruction(JSON conf) {
		return new AbstractInstructionFuture() {

			protected ListenableFuture<JSON> loadJtl(
					final AsyncExecutionContext<JSON> context, final String file) {
				final AsyncExecutionContext<JSON> ctx = context
						.getMasterContext();
				List<ListenableFuture<JSON>> ll = new ArrayList<>();
				Callable<JSON> cc = new Callable<JSON>() {

					@Override
					public JSON call() throws Exception {
						final JtlCompiler compiler = new JtlCompiler(builder,
								false, false, true);
						InstructionFuture<JSON> inst = compiler.parse(new File(
								file));
						return inst.call(ctx, immediateCheckedFuture(conf))
								.get();
					}
				};
				return context.executor().submit(cc);
			}

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						JSONType type = input.getType();
						if (type == JSONType.STRING) {
							return loadJtl(context, stringValue(input));
						}
						if (type == JSONType.ARRAY) {
							List<ListenableFuture<JSON>> ll = new ArrayList<>();
							for (JSON j : (JSONArray) input) {
								if (j.getType() == JSONType.STRING) {
									ll.add(loadJtl(context, stringValue(j)));
								}
							}
							return transform(allAsList(ll),
									new AsyncFunction<List<JSON>, JSON>() {
										@Override
										public ListenableFuture<JSON> apply(
												List<JSON> input)
												throws Exception {
											JSONArray arr = builder.array(null);
											for (JSON j : input) {
												arr.add(j);
											}
											return immediateCheckedFuture(arr);
										}
									});
						}
						return immediateCheckedFuture(builder.value());
					}
				});
			}

		};
	}

	public InstructionFuture<JSON> loadModule(final JSONObject conf) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				List<ListenableFuture<JSON>> ll = new ArrayList<>(3);
				InstructionFuture<JSON> key = context.getdef(JTL_INTERNAL_KEY);
				InstructionFuture<JSON> name = context.getdef("1");
				if (key != null) {
					ll.add(key.call(context, data));
				} else {
					ll.add(name.call(context, data));
				}
				ll.add(name.call(context, data));
				InstructionFuture<JSON> ci = context.getdef("2");
				if (ci != null) {
					ll.add(ci.call(context, data));
				}
				return transform(allAsList(ll),
						new AsyncFunction<List<JSON>, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								Iterator<JSON> jit = input.iterator();
								String key = stringValue(jit.next());

								String name = stringValue(jit.next());
								JSONObject config = (JSONObject) (jit.hasNext() ? jit
										.next() : null);
								ModuleLoader ml = ModuleLoader.getInstance(
										builder, conf);
								AsyncExecutionContext<JSON> modctx = context
										.getMasterContext()
										.getNamedContext(key);
								int n = ml.create(name, modctx, config);
								return immediateCheckedFuture(builder.value(n));
							}
						});

			}
		};

	}

	private JSON applyRegex(Pattern p, JSON j) {
		switch(j.getType()) {
		case STRING:
			String ins = ((JSONValue) j).stringValue();
			if (ins != null) {
				Matcher m = p.matcher(ins);
				if (m.find()) {
					JSONArray unbound = builder.array(null);
					int n = m.groupCount();
					for (int i = 0; i <= n; ++i) {
						JSON r = builder.value(m.group(i));
						unbound.add(r);
					}
					return unbound;
				}
			} break;
		case OBJECT: {
			JSONObject unbound = builder.object(null);
			JSONObject inarr = (JSONObject) j;
			for (Pair<String, JSON> jj : inarr) {
				JSON r = applyRegex(p, jj.s);
				if (r.isTrue())
					unbound.put(jj.f, r);
			}
			return unbound;
		}
		case FRAME:
		case ARRAY: {
			JSONArray unbound = builder.array(null);
			JSONArray inarr = (JSONArray) j;
			boolean any = false;
			for (JSON k : inarr) {
				JSON r = applyRegex(p, k);
				if(r.isTrue()) unbound.add(r);
//				any |= r.isTrue();
//				unbound.add(r);
			}
//			return any ? unbound : builder.array(null);
			return unbound;
		}
		default:

		}
 		return builder.array(null);

	}

	public InstructionFuture<JSON> reMatch(final String p,
			final InstructionFuture<JSON> d) {
		final Pattern pattern = Pattern.compile(p);
		return new FramingInstructionFuture() {
			@Override
			public ListenableFuture<JSON> callItem(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(d.call(context, data),
						new AsyncFunction<JSON, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(JSON input)
									throws Exception {
								return immediateCheckedFuture(applyRegex(
										pattern, input));
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> negate(InstructionFuture<JSON> ii) {
		// TODO:: need to actually apply negation here: boring but eventually
		// necessary
		return ii;
	}

	public InstructionFuture<JSON> variable(final String name) {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> t) {
				try {
					return context.lookup(name, t);
				} catch (Exception e) {
					return immediateFailedCheckedFuture(new ExecutionException(
							e));
				}
			}
		};
	}

	public InstructionFuture<JSON> deferred(InstructionFuture<JSON> inst,
			AsyncExecutionContext<JSON> context, final ListenableFuture<JSON> t) {
		return memo(new DeferredCall(inst, context, t));
	}

//	protected InstructionFuture<JSON> unwrap() {
		
//	}
	protected AsyncExecutionContext<JSON> setupArguments(
			AsyncExecutionContext<JSON> ctx, 
			final String name, 
			final List<InstructionFuture<JSON>> iargs,
			final ListenableFuture<JSON> data) {
		AsyncExecutionContext<JSON> context = ctx
				.createChild(true);
		context.define("0", value(name));
		int cc = 1;
		for (InstructionFuture<JSON> i : iargs) {
			// the arguments themselves should be evaluated
			// with the parent context
			// instructions can be unwrapped if the callee wants a
			// a function, rather than a value from the arument list
			InstructionFuture<JSON> inst = deferred(i, ctx, data);
			// but define the argument in the child context
			
			// this strategy allows numbered argument (ie.) $1 to be used
			context.define(Integer.toString(cc++), inst);
		}
		return context;	
	}
	public InstructionFuture<JSON> function(final String name,
			final List<InstructionFuture<JSON>> iargs) {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> data) throws ExecutionException {
				// System.err.println("calling function '" + name + "'.");
				InstructionFuture<JSON> func = context.getdef(name);
				if (func == null) {
					System.err.println("function '" + name + "' not found.");
					return immediateCheckedFuture(null);
				}
				AsyncExecutionContext<JSON> childContext = setupArguments(context, name, iargs,data);
				return func.call(childContext, data);
			}
		};
	}

	public static InstructionFuture<JSON> value(final ListenableFuture<JSON> o) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return o;
			}
		};
	}

	public static InstructionFuture<JSON> value(JSON o) {
		return value(immediateCheckedFuture(o));
	}

	public InstructionFuture<JSON> value() {
		return value(builder.value());
	}

	public InstructionFuture<JSON> value(final Boolean val) {
		return value(builder.value(val));
	}

	public InstructionFuture<JSON> value(final Long val) {
		return value(builder.value(val));
	}

	public InstructionFuture<JSON> value(final Double val) {
		return value(builder.value(val));
	}

	public InstructionFuture<JSON> value(final String val) {
		return value(builder.value(val));
	}

	public InstructionFuture<JSON> number(TerminalNode i, TerminalNode d) {
		if (i != null)
			return value(Long.parseLong(i.getText()));
		return value(Double.parseDouble(d.getText()));
	}

	public InstructionFuture<JSON> array(final List<InstructionFuture<JSON>> ch) {
		return new FramingInstructionFuture() {
			@Override
			public ListenableFuture<JSON> callItem(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> t) throws ExecutionException {
				List<ListenableFuture<JSON>> args = new ArrayList<>();
				for (InstructionFuture<JSON> i : ch) {
					args.add(i.call(context, t));
				}
				return transform(allAsList(args),
						new AsyncFunction<List<JSON>, JSON>() {
							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								JSONArray arr = builder.array(null,
										input.size());
								for (JSON j : input) {
									arr.add(j == null ? builder.value() : j);
								}
								return immediateCheckedFuture(arr);
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> dyadic(InstructionFuture<JSON> left,
			InstructionFuture<JSON> right, DyadicAsyncFunction<JSON> f) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> parent)
					throws ExecutionException {
				return transform(
						allAsList(left.call(context, parent),
								right.call(context, parent)),
						new KeyedAsyncFunction<List<JSON>, JSON, DyadicAsyncFunction<JSON>>(
								f) {

							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws ExecutionException {
								Iterator<JSON> it = input.iterator();
								JSON l = it.next();
								JSON r = it.next();
								if (l == null || r == null)
									return immediateCheckedFuture(null);

								try {
									return immediateCheckedFuture(k.invoke(
											context, l, r));
								} catch (ExecutionException e) {
									return immediateFailedFuture(e);
								}
							}
						});
			}
		};

	}

	public InstructionFuture<JSON> unique() {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						switch (input.getType()) {
						case FRAME:
						case ARRAY: {
							Collection<JSON> cc = ((JSONArray) input)
									.collection();
							Set<JSON> ss = new LinkedHashSet<>();
							for (JSON j : cc) {
								ss.add(j);
							}
							return immediateCheckedFuture(builder.array(
									(JSON) input, ss));

						}
						default:
							return immediateCheckedFuture(input.cloneJSON());
						}
					}

				});
			}

		};
	}

	public InstructionFuture<JSON> count() {
		return new AbstractInstructionFuture() {
			JSON cnt(JSON j) {
				switch (j.getType()) {
				case FRAME: {
					Frame f = builder.frame();
					for (JSON jj : (Frame) j) {
						f.add(cnt(jj));
					}
					return f;
				}
				case ARRAY:
					return builder.value(((JSONArray) j).collection().size());
				case OBJECT:
					return builder.value(((JSONObject) j).map().size());
				default:
					return builder.value(1);
				}

			}

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> arg = context.getdef("1");
				if (arg != null) {
					data = arg.call(context, data);
				}
				return transform(data, new AsyncFunction<JSON, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						return immediateCheckedFuture(cnt(input));
					}
				});
			}
		};
	}

	public static InstructionFuture<JSON> conditional(
			final InstructionFuture<JSON> test,
			final InstructionFuture<JSON> trueI,
			final InstructionFuture<JSON> falseI) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> data)
					throws ExecutionException {
				return transform(test.call(context, data),
						new AsyncFunction<JSON, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(JSON input)
									throws Exception {
								if (input.isTrue()) {
									return trueI.call(context, data);
								} else {
									return falseI.call(context, data);
								}
							}
						});
			}
		};
	}

	@SafeVarargs
	public static InstructionFuture<JSON> chain(
			final InstructionFuture<JSON>... inst) {

		InstructionFuture<JSON> chain = null;
		for (InstructionFuture<JSON> ii : inst) {
			if (chain == null) {
				chain = ii;
			} else {
				final InstructionFuture<JSON> pp = chain;
				chain = new AbstractInstructionFuture() {

					@Override
					public ListenableFuture<JSON> call(
							AsyncExecutionContext<JSON> context,
							ListenableFuture<JSON> data)
							throws ExecutionException {
						return ii.call(context, pp.call(context, data));
					}
				};
			}
		}
		return chain;
	}

	public InstructionFuture<JSON> sequence(
			final InstructionFuture<JSON>... inst) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				List<ListenableFuture<JSON>> ll = new ArrayList<>();
				for (InstructionFuture<JSON> ii : inst) {
					ll.add(ii.call(context, data));
				}
				return transform(allAsList(ll),
						new AsyncFunction<List<JSON>, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								Frame f = builder.frame();
								for (JSON j : input) {
									f.add(j);
								}
								return immediateCheckedFuture(f);
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> params() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				boolean done = false;

				List<InstructionFuture<JSON>> ll = new ArrayList<>();
				for (int i = 1; done == false && i < 4096; ++i) {
					InstructionFuture<JSON> ci = context.getdef(Integer
							.toString(i));
					if (ci == null)
						done = true;
					else {
						ll.add(ci);
					}
				}
				return sequence(ll.toArray(new InstructionFuture[ll.size()]))
						.call(context, data);
			}

		};

	}

	public InstructionFuture<JSON> defaultError() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				final InstructionFuture<JSON> first = context.getdef("1");
				final InstructionFuture<JSON> second = context.getdef("2");
				if (first == null) {
					JSONObject obj = builder.object(null);
					obj.put("status", builder.value(500L));
					obj.put("message", builder.value("unknown error"));
					return immediateCheckedFuture(obj);
				}
				final List<ListenableFuture<JSON>> ll = new ArrayList<>();
				ll.add(first.call(context, data));
				if (second != null) {
					ll.add(second.call(context, data));
				}
				return transform(allAsList(ll),
						new AsyncFunction<List<JSON>, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								JSONObject obj = builder.object(null);
								Iterator<JSON> jit = input.iterator();
								JSON f = jit.next();
								JSON s = jit.hasNext() ? jit.next() : null;
								if (f.isNumber()) {
									obj.put("status", builder
											.value(((JSONValue) f).longValue()));
									if (s != null) {
										obj.put("message", builder
												.value(((JSONValue) s)
														.stringValue()));
									} else {
										obj.put("message",
												builder.value("an unknown error has occurred"));

									}
								} else {
									obj.put("status", builder.value(500L));
									obj.put("message", builder
											.value(((JSONValue) f)
													.stringValue()));

								}
								return immediateCheckedFuture(obj);
							}
						});
			}
		};
	}

	class ObjectInstructionFuture extends AbstractInstructionFuture {
		final InstructionFutureFactory factory;
		// protected Set<String> keys = new HashSet<>();
		final List<Pair<String, InstructionFuture<JSON>>> ll;
		final JSONBuilder builder;

		public ObjectInstructionFuture(InstructionFutureFactory factory,
				final List<Pair<String, InstructionFuture<JSON>>> ll,
				JSONBuilder builder) {
			this.factory = factory;
			this.builder = builder;
			this.ll = ll;
		}

		protected ListenableFuture<JSON> dataObject(
				final AsyncExecutionContext<JSON> context,
				final ListenableFuture<JSON> data) throws ExecutionException {
			List<ListenableFuture<Pair<String, JSON>>> insts = new ArrayList<>(
					ll.size());
			for (Pair<String, InstructionFuture<JSON>> ii : ll) {
				final String kk = ii.f;
				final AsyncExecutionContext<JSON> newc = context
						.createChild(false);
				InstructionFuture<JSON> ki = value(kk);
				newc.define(JTL_INTERNAL_KEY,ki);
				newc.define("key", ki);
				ListenableFuture<Pair<String, JSON>> lf = transform(
						ii.s.call(newc, data),
						new AsyncFunction<JSON, Pair<String, JSON>>() {
							@Override
							public ListenableFuture<Pair<String, JSON>> apply(
									JSON input) throws Exception {
								input.setName(kk);
								return immediateCheckedFuture(new Pair(kk,
										input));
							}
						});
				insts.add(lf);
			}

			return transform(allAsList(insts),
					new AsyncFunction<List<Pair<String, JSON>>, JSON>() {
						@Override
						public ListenableFuture<JSON> apply(
								List<Pair<String, JSON>> input)
								throws Exception {
							JSONObject obj = builder.object(null, input.size());

							for (Pair<String, JSON> d : input) {
								obj.put(d.f,
										d.s != null ? d.s : builder.value());
							}
							return immediateCheckedFuture(obj);
						}
					});
		}

		@Override
		public ListenableFuture<JSON> call(
				final AsyncExecutionContext<JSON> context,
				final ListenableFuture<JSON> data) throws ExecutionException {
			return dataObject(context, data);
		}

	}

	class ContextObjectInstructionFuture extends AbstractInstructionFuture {
		final InstructionFutureFactory factory;
		final List<Pair<String, InstructionFuture<JSON>>> ll;
		final JSONBuilder builder;
		final boolean imported;

		public ContextObjectInstructionFuture(InstructionFutureFactory factory,
				final List<Pair<String, InstructionFuture<JSON>>> ll,
				JSONBuilder builder, boolean imported) {
			this.factory = factory;
			this.builder = builder;
			this.ll = ll;
			this.imported = imported;
		}

		protected ListenableFuture<JSON> contextObject(
				final AsyncExecutionContext<JSON> ctx,
				final ListenableFuture<JSON> data) throws ExecutionException {
			InstructionFuture<JSON> defaultInstruction = null;
			InstructionFuture<JSON> init = null;
			List<InstructionFuture<JSON>> imperitives = new ArrayList<>(
					ll.size());
			final AsyncExecutionContext<JSON> context = imported ? ctx
					.getMasterContext() : ctx.createChild(false);

			for (Pair<String, InstructionFuture<JSON>> ii : ll) {
				final String k = ii.f;
				final InstructionFuture<JSON> inst = ii.s;

				if (k.equals("!init")) {
					init = memo(inst);
					context.define("init", inst);
				} else if (k.equals("_")) {
					defaultInstruction = inst;
				} else if (k.startsWith("!")) {
					// variable, (almost) immediate evaluation
					InstructionFuture<JSON> imp = memo(inst);
					context.define(k.substring(1), imp);
					imperitives.add(imp);
				} else if (k.startsWith("$")) {
					// variable, deferred evaluation
					context.define(k.substring(1),
							factory.deferred(inst, context, data));
				} else {
					// define a function
					context.define(k, inst);
				}
			}
			try {
				// ensure that init is completed so that any modules are
				// installed
				// and imports imported
				final InstructionFuture<JSON> finst = defaultInstruction;
				AsyncFunction<List<JSON>, JSON> runner = new AsyncFunction<List<JSON>, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(List<JSON> input)
							throws Exception {
						return finst != null && imported == false ? finst.call(
								context, data) : immediateCheckedFuture(builder
								.value(true));
					}
				};
				if (init != null) {
					return transform(init.call(context, context.config()),
							new AsyncFunction<JSON, JSON>() {
								@Override
								public ListenableFuture<JSON> apply(JSON input)
										throws Exception {
									// input is the result of init, don't care,
									// really
									List<ListenableFuture<JSON>> ll = new ArrayList<>();
									for (InstructionFuture<JSON> imp : imperitives) {
										ll.add(imp.call(context, data));
									}
									if (!ll.isEmpty())
										return transform(allAsList(ll), runner);
									if (finst != null)
										return finst.call(context, data);
									return immediateCheckedFuture(builder
											.value(true));
								}
							});
				}

				List<ListenableFuture<JSON>> ll = new ArrayList<>();
				for (InstructionFuture<JSON> imp : imperitives) {
					ll.add(imp.call(context, data));
				}
				if (!ll.isEmpty())
					return transform(allAsList(ll), runner);
				if (finst != null)
					return finst.call(context, data);
				return immediateCheckedFuture(builder.value(true));
			} catch (ExecutionException e) {
				InstructionFuture<JSON> error = context.getdef("error");
				if (error == null) {
					System.err
							.println("WTF!!!!???? no error handler is defined!");
					throw new RuntimeException(
							"WTF!!!!???? no error handler is defined!");
				}
				AsyncExecutionContext<JSON> ec = context.createChild(true);
				ec.define("0", value("error"));
				ec.define("1", value(500L));
				ec.define("2", value(e.getLocalizedMessage()));
				return error.call(ec, data);
			}
		}

		@Override
		public ListenableFuture<JSON> call(
				final AsyncExecutionContext<JSON> context,
				final ListenableFuture<JSON> data) throws ExecutionException {
			return contextObject(context.createChild(false), data);
		}

	}

	public InstructionFuture<JSON> stepParent() {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						JSON p = input.getParent();
						JSON res = p == null ? builder.value() : p;
						return immediateCheckedFuture(res);
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> filter() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						InstructionFuture<JSON> fexp = context.getdef("1");
						if (fexp != null) {
							fexp = fexp.unwrap(context);
							List<ListenableFuture<JSON>> ll = new ArrayList<>();
							if (input instanceof Frame
									|| input instanceof JSONArray) {
								for (JSON j : (JSONArray) input) {
									// Pair<JSON,JSON> pp = new Pair<JSON,
									// JSON>(j, s)
									ListenableFuture<JSON> jj = immediateCheckedFuture(j);
									ll.add(transform(
											fexp.call(context, jj),
											new KeyedAsyncFunction<JSON, JSON, JSON>(
													j) {

												@Override
												public ListenableFuture<JSON> apply(
														JSON input)
														throws Exception {
													if (input == null
															|| input.isTrue() == false) {
														return immediateCheckedFuture(builder
																.value());
													}
													return immediateCheckedFuture(k);
												}
											}));
								}
							} else {
								ListenableFuture<JSON> jj = immediateCheckedFuture(input);
								ll.add(transform(
										fexp.call(context, jj),
										new KeyedAsyncFunction<JSON, JSON, JSON>(
												input) {

											@Override
											public ListenableFuture<JSON> apply(
													JSON input)
													throws Exception {
												if (input == null
														|| input.isTrue() == false) {
													return immediateCheckedFuture(builder
															.value());
												}
												return immediateCheckedFuture(k);
											}
										}));
							}
							if (ll.size() == 0)
								return immediateCheckedFuture(builder.value());
							return transform(allAsList(ll),
									new AsyncFunction<List<JSON>, JSON>() {

										@Override
										public ListenableFuture<JSON> apply(
												List<JSON> input)
												throws Exception {
											Frame frame = builder.frame();
											if (input.size() == 1) {
												Iterator<JSON> jit = input
														.iterator();
												JSON j = jit.next();
												if (j != null && j.isTrue()) {
													frame.add(j, true);
												}
											} else {
												for (JSON j : input) {
													if (j != null && j.isTrue()) {
														frame.add(j, true);
													}
												}
											}
											return immediateCheckedFuture(frame);
										}
									});
						} else {
							return immediateCheckedFuture(input);
						}
					}
				});
			}

		};
	}

	public InstructionFuture<JSON> collate() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						JSONType type = input.getType();
						if (type == JSONType.ARRAY || type == JSONType.FRAME) {
							Map<String, Collection<JSON>> cols = new LinkedHashMap<>();
							int c = 0;
							for (JSON j : (JSONArray) input) {
								if (j.getType() == JSONType.OBJECT) {
									for (Pair<String, JSON> pp : (JSONObject) j) {
										Collection<JSON> col = cols.get(pp.f);
										if (col == null) {
											col = new ArrayList<>();
											cols.put(pp.f, col);
										}
										int n = c - col.size();
										if (n > 0) {
											for (int i = 0; i < n; ++i) {
												col.add(builder.value());
											}
										}
										col.add(pp.s);

									}
								}
							}
							JSONObject obj = builder.object(null);
							for (Map.Entry<String, Collection<JSON>> ee : cols
									.entrySet()) {
								obj.put(ee.getKey(),
										builder.array(null, ee.getValue()));
							}
							return immediateCheckedFuture(obj);
						}
						return immediateCheckedFuture(input);
					}
				});
			}

		};
	}

	public InstructionFuture<JSON> sort(boolean reverse) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					Comparator<Pair<JSON, JSON>> comparator = new Comparator<Pair<JSON, JSON>>() {

						@Override
						public int compare(Pair<JSON, JSON> o1,
								Pair<JSON, JSON> o2) {
							return reverse ? o2.f.compareTo(o1.f) : o1.f
									.compareTo(o2.f);
						}
					};

					protected ListenableFuture<JSON> sort(
							ArrayList<Pair<JSON, JSON>> ll,JSON parent) {
						ll.sort(comparator);
						JSONArray result = builder.array(parent);
						for (Pair<JSON, JSON> pp : ll) {
							result.add(pp.s);
						}
						return immediateCheckedFuture(result);
					}

					@Override
					public ListenableFuture<JSON> apply(final JSON input)
							throws Exception {
						if (input.getType() == JSONType.ARRAY
								|| input.getType() == JSONType.FRAME) {
							InstructionFuture<JSON> fi = context.getdef("1");
							if (fi != null) {
								fi = fi.unwrap(context);
								List<ListenableFuture<Pair<JSON, JSON>>> ll = new ArrayList<>();
								for (JSON j : (JSONArray) input) {
									ll.add(transform(
											fi.call(context,
													immediateCheckedFuture(j)),
											new KeyedAsyncFunction<JSON, Pair<JSON, JSON>, JSON>(
													j) {
												public ListenableFuture<Pair<JSON, JSON>> apply(
														JSON ji) {
													return immediateCheckedFuture(new Pair(
															ji, k));
												}
											}));
								}
								return transform(
										allAsList(ll),
										new AsyncFunction<List<Pair<JSON, JSON>>, JSON>() {

											@Override
											public ListenableFuture<JSON> apply(
													List<Pair<JSON, JSON>> inp)
													throws Exception {
												ArrayList<Pair<JSON, JSON>> ll = (input instanceof ArrayList) ? (ArrayList<Pair<JSON, JSON>>) input
														: new ArrayList<>(inp);
												return sort(ll,input);
											}
										});
							}
							Collection<JSON> cc = ((JSONArray) input)
									.collection();
							ArrayList<Pair<JSON, JSON>> ll = new ArrayList<>();
							for (JSON j : cc) {
								ll.add(new Pair<JSON, JSON>(j, j));
							}
							return sort(ll,input);
						} else {
							return immediateCheckedFuture(input);
						}
					}

				});
			}

		};
	}

	public InstructionFuture<JSON> stepSelf() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						return immediateCheckedFuture(input.cloneJSON());
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> recursDown() {
		return new FramingInstructionFuture() {
			protected void recurse(Frame unbound, JSON j) {
				JSONType type = j.getType();
				switch (type) {
				case FRAME:
				case ARRAY: {
					JSONArray a = (JSONArray) j;
					for (JSON jj : a) {
						unbound.add(jj.cloneJSON());
						recurse(unbound, jj);
					}
				}
					break;
				case OBJECT: {
					JSONObject a = (JSONObject) j;
					for (Pair<String, JSON> jj : a) {
						unbound.add(jj.s);
						recurse(unbound, jj.s.cloneJSON());
					}
				}
					break;
				}
			}

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						Frame unbound = builder.frame();
						switch (input.getType()) {
						case FRAME:
						case ARRAY: {
							JSONArray arr = (JSONArray) input;

							for (JSON j : arr) {
								unbound.add(j);
								recurse(unbound, j);
							}
						}
							break;
						case OBJECT: {
							JSONObject arr = (JSONObject) input;

							for (Pair<String, JSON> jj : arr) {
								unbound.add(jj.s);
								recurse(unbound, jj.s);
							}
						}
							break;
						}
						return immediateCheckedFuture(unbound);
					}
				});
				// return callItem(context, data);
			}

			// @Override
			public ListenableFuture<JSON> callItem(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					protected void recurse(Frame unbound, JSON j) {
						JSONType type = j.getType();
						switch (type) {
						case ARRAY: {
							JSONArray a = (JSONArray) j;
							for (JSON jj : a) {
								unbound.add(jj);
								recurse(unbound, jj);
							}
						}
							break;
						case OBJECT:
							JSONObject a = (JSONObject) j;
							for (Pair<String, JSON> jj : a) {
								unbound.add(jj.s);
								recurse(unbound, jj.s);
							}
						}
					}

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						if (input == null)
							return immediateCheckedFuture(null);
						Frame unbound = builder.frame();
						unbound.add(input);
						recurse(unbound, input);
						return immediateCheckedFuture(unbound);
					}
				});
			}
		};
	}

	// cache
	public InstructionFuture<JSON> recursUp() {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					protected void recurse(Frame unbound, JSON j) {
						if (j == null)
							return;
						unbound.add(j);
						JSON p = j.getParent();
						recurse(unbound, p);
					}

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						Frame unbound = builder.frame();
						recurse(unbound, input.getParent());
						return immediateCheckedFuture(unbound);
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> mapChildren() {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						switch (input.getType()) {
						case FRAME:
							// return callSuper(context, data);
						case ARRAY: {
							Frame frame = builder.frame();
							for (JSON j : (JSONArray) input) {
								frame.add(j);
							}
							return immediateCheckedFuture(frame);

						}
						case OBJECT: {
							JSONObject obj = builder.object(null);
							for (Pair<String, JSON> j : (JSONObject) input) {
								obj.put(j.f, j.s);
							}
							return immediateCheckedFuture(obj);

						}
						default:
						}
						return immediateCheckedFuture(builder.value());

					}
				});
			}
		};
	}

	// cache
	public InstructionFuture<JSON> stepChildren() {

		return new AbstractInstructionFuture() {

			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						Frame frame = builder.frame(input);
						switch (input.getType()) {
						case FRAME: {
							for (JSON j : (JSONArray) input) {
								switch (j.getType()) {
								case ARRAY:
								case FRAME:
									for (JSON k : (JSONArray) j) {
										frame.add(k);
									}
									break;
								// return immediateCheckedFuture(frame);
								case OBJECT: {
									frame.add(j);
//									for (Pair<String, JSON> pp : (JSONObject) j) {
//										frame.add(pp.s);
//									}
								}
								}
							}
							break;
						}
						case ARRAY: {
							for (JSON j : (JSONArray) input) {
								frame.add(j);
							}
							break;
						}
						case OBJECT: {
							for (Pair<String, JSON> j : (JSONObject) input) {
								frame.add(j.s);
							}
							break;
						}
						default:
						}
						return immediateCheckedFuture(frame);
					}
				});
			}

//			@Override
			public ListenableFuture<JSON> callItem(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						JSONType type = input.getType();
						switch (type) {
						case ARRAY: {
							Frame unbound = builder.frame();
							JSONArray arr = (JSONArray) input;
							for (JSON j : arr) {
								unbound.add(j);
							}
							return immediateCheckedFuture(input);
						}
						case OBJECT: {
							Frame unbound = builder.frame();
							JSONObject obj = (JSONObject) input;
							for (Pair<String, JSON> ee : obj) {
								unbound.add(ee.s);
							}
							return immediateCheckedFuture(unbound);
						}
						default:
							Frame unbound = builder.frame();
							return immediateCheckedFuture(unbound);
						}
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> string(final String label) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return immediateCheckedFuture(builder.value(label));
			}
		};
	}

	public InstructionFuture<JSON> get(final String label) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					ListenableFuture<JSON> get(JSONObject j)
							throws ExecutionException {
						JSON r = j.get(label);
						if (!(r == null || r.getType() == JSONType.NULL)) {
							return immediateCheckedFuture(r);
						} /*
						 * InstructionFuture<JSON> inst = context.getdef(label);
						 * if (inst != null) { return inst.call(context, data);
						 * }
						 */
						 return null;
						// return immediateCheckedFuture(builder.value());
						// return null;
					}

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						switch (input.getType()) {
						case OBJECT: {
							JSONObject obj = (JSONObject) input;
							ListenableFuture<JSON> lf = get(obj);
							if (lf != null)
								return lf;
							return immediateCheckedFuture(builder.value());
						}
						case FRAME: {
							JSONArray arr = (JSONArray) input;
							List<ListenableFuture<JSON>> ll = new ArrayList<>();
							for (JSON j : arr) {

								if (j.getType() == JSONType.OBJECT) {
									JSONObject obj = (JSONObject) j;
									ListenableFuture<JSON> res = get(obj);
									if (res != null)
										ll.add(res);
								}
							}
							if (ll.size() > 0)
								return transform(allAsList(ll),
										new AsyncFunction<List<JSON>, JSON>() {

											@Override
											public ListenableFuture<JSON> apply(
													List<JSON> input)
													throws Exception {
												Frame unbound = builder.frame();
												for (JSON j : input) {
													unbound.add(j);
												}
												return immediateCheckedFuture(unbound);
											}
										});
							return immediateCheckedFuture(builder.value());
						}

						case ARRAY:
						default:
							InstructionFuture<JSON> inst = context
									.getdef(label);
							if (inst != null) {
								return inst.call(context, data);
							}
							return immediateCheckedFuture(builder.value());
						}
						// return immediateCheckedFuture(builder.value());
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> ternary(final InstructionFuture<JSON> c,
			final InstructionFuture<JSON> a, final InstructionFuture<JSON> b) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(c.call(context, data),
						new AsyncFunction<JSON, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(JSON input)
									throws Exception {
								return input.isTrue() ? a.call(context, data)
										: b.call(context, data);
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> object(
			final List<Pair<String, InstructionFuture<JSON>>> ll,
			boolean forceContext) throws ExecutionException {
		boolean isContext = forceContext;
		if (isContext == false)
			for (Pair<String, InstructionFuture<JSON>> ii : ll) {
				if ("_".equals(ii.f)) {
					isContext = true;
					break;
				}
			}
		return isContext ? new ContextObjectInstructionFuture(this, ll,
				builder, forceContext) : new ObjectInstructionFuture(this, ll,
				builder);
	}

	static Long longValue(JSON j) {
		Long l = null;
		switch (j.getType()) {
		case LONG:
		case DOUBLE:
		case STRING:
			return ((JSONValue) j).longValue();
		}
		return null;
	}

	static Double doubleValue(JSON j) {
		Long l = null;
		switch (j.getType()) {
		case LONG:
		case DOUBLE:
		case STRING:
			return ((JSONValue) j).doubleValue();
		}
		return null;
	}

	static String stringValue(JSON j) {
		Long l = null;
		switch (j.getType()) {
		case LONG:
		case DOUBLE:
		case STRING:
			return ((JSONValue) j).stringValue();
		}
		return null;
	}

	/*
	 * public InstructionFuture<JSON> jtl(final InstructionFuture<JSON> inst) {
	 * return new AbstractInstructionFuture() {
	 * 
	 * @Override public ListenableFuture<JSON> callItem(
	 * AsyncExecutionContext<JSON> context, ListenableFuture<JSON> data) throws
	 * ExecutionException { return transform(inst.call(context, data), new
	 * AsyncFunction<JSON, JSON>() {
	 * 
	 * @Override public ListenableFuture<JSON> apply( JSON input) throws
	 * Exception { // sealResult(input); input.lock(); return
	 * immediateCheckedFuture(input); } }); } }; }
	 */
	/*
	 * public InstructionFuture<JSON> object(List<Pair<String,
	 * InstructionFuture<JSON>>> ll) {
	 * 
	 * try { return new InstructionFutureValue<JSON>(factory.object(ins)); }
	 * catch (ExecutionException e) { return new InstructionFutureValue<JSON>(
	 * factory.value("ExecutionException during visitObject: " +
	 * e.getLocalizedMessage())); }
	 * 
	 * 
	 * }
	 */
	public InstructionFuture<JSON> relpath(final InstructionFuture<JSON> a,
			final InstructionFuture<JSON> b) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return b.call(context, a.call(context, data));
			}

		};
	}

	public InstructionFuture<JSON> tostr(final InstructionFuture<JSON> inst) {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(inst.call(context, data),
						new AsyncFunction<JSON, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(JSON input)
									throws Exception {
								return immediateCheckedFuture(builder
										.value(input.toString()));
							}

						});
			}
		};
	}

	public InstructionFuture<JSON> strc(final List<InstructionFuture<JSON>> ii) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				List<ListenableFuture<JSON>> rr = new ArrayList<>(ii.size());
				for (InstructionFuture<JSON> inst : ii) {
					rr.add(inst.call(context, data));
				}
				return transform(Futures.allAsList(rr),
						new AsyncFunction<List<JSON>, JSON>() {
							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								StringBuilder sb = new StringBuilder();
								for (JSON j : input) {
									if (j.getType() != JSONType.NULL) {
										sb.append(((JSONValue) j).stringValue());
									}
								}
								return immediateCheckedFuture(builder.value(sb
										.toString()));
							}
						});
			}
		};
	}

	public InstructionFuture<JSON> abspath(InstructionFuture<JSON> inst) {
		return new AbstractInstructionFuture() {
			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {

				return transform(data, new AsyncFunction<JSON, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						return inst.call(context,context.getMasterContext().dataContext());
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> union(final List<InstructionFuture<JSON>> seq) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				final List<ListenableFuture<JSON>> fut = new ArrayList<>();
				for (InstructionFuture<JSON> ii : seq) {
					fut.add(ii.call(context, data));
				}
				return transform(Futures.allAsList(fut),
						new AsyncFunction<List<JSON>, JSON>() {

							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								JSONArray unbound = builder.array(null, true);
								for (JSON j : input) {
									unbound.add(j);
								}
								return immediateCheckedFuture(unbound);
							}

						});
			}
		};
	}

	public InstructionFuture<JSON> dereference(final InstructionFuture<JSON> a,
			final InstructionFuture<JSON> b) {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				return transform(
						allAsList(a.call(context, data), b.call(context, data)),
						new AsyncFunction<List<JSON>, JSON>() {
							@Override
							public ListenableFuture<JSON> apply(List<JSON> input)
									throws Exception {
								Iterator<JSON> it = input.iterator();
								JSON ra = it.next();
								JSON rb = it.next();
								JSONType btype = rb.getType();
								if (btype == JSONType.NULL
										|| btype == JSONType.OBJECT)
									return immediateCheckedFuture(builder
											.value());

								Frame unbound = builder.frame(ra);
								switch (ra.getType()) {
								case FRAME:
								case ARRAY: {
									JSONArray larr = (JSONArray) ra;
									if (rb instanceof JSONArray) {
										for (JSON j : (JSONArray) rb) {
											JSONType jtype = j.getType();
											switch (jtype) {
											case STRING:
											case LONG:
											case DOUBLE: {
												Long l = ((JSONValue) j)
														.longValue();
												if (l != null) {
													if(l<0) l = ((l+larr.size()) % larr.size());
													unbound.add(larr.get(l
															.intValue()));
												}
											}
												break;
											case ARRAY: {
												JSONArray jarr = (JSONArray) j;
												if (jarr.size() == 2) {
													JSON ja = jarr.get(0);
													JSON jb = jarr.get(1);
													if (ja != null
															&& ja.isValue()
															&& jb != null
															&& jb.isValue()) {
														Long la = ((JSONValue) ja)
																.longValue();
														Long lb = ((JSONValue) jb)
																.longValue();
														if (la != null && lb != null) {
															// adjust for negative index
															if(la<0) la = ((la+larr.size()) % larr.size());
															if(lb<0) lb = ((lb+larr.size()) % larr.size());
															int inc = la < lb ? 1
																	: -1;
															for (; (la-inc) != lb; la += inc) {
																unbound.add(larr.get(la
																		.intValue()));
															}
														}
													}
												}
											} break;
											default:
											}
										}
									}
								} break;
								case OBJECT: {
									JSONObject obj = (JSONObject) ra;
									if (rb instanceof JSONArray) {
										for (JSON j : (JSONArray) rb) {
											JSONType jtype = j.getType();
											switch (jtype) {
											case STRING:
											case LONG:
											case DOUBLE:
												String s = ((JSONValue) j)
														.stringValue();
												unbound.add(obj.get(s));
												break;
											default:
											}
										}
									}
								}
									break;
								default:
								}
								int n = unbound.size();
								if (n == 0)
									return immediateCheckedFuture(builder
											.value());
								if (n == 1)
									return immediateCheckedFuture(unbound
											.get(0));
								return immediateCheckedFuture(unbound);
							}
						});
			}
		};
	}
	
	public InstructionFuture<JSON> omap() {
		return new AbstractInstructionFuture() {

			@Override
			public ListenableFuture<JSON> call(
					final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> data) throws ExecutionException {
				return transform(data, new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						if(input.getType() == JSONType.ARRAY) {
							InstructionFuture<JSON> mf = context.getdef("1");
							mf = mf.unwrap(context);
							List<ListenableFuture<Pair<JSON,JSON>>> ll = new ArrayList<>();
							for(JSON j: (JSONArray) input) {
								AsyncExecutionContext<JSON> cc = context.createChild(true);
								cc.define("key", value(j));
								ListenableFuture<JSON> jif = immediateCheckedFuture(j);
								ll.add(transform(allAsList(jif,mf.call(cc,data)), new AsyncFunction<List<JSON>, Pair<JSON,JSON>>() {

									@Override
									public ListenableFuture<Pair<JSON, JSON>> apply(
											List<JSON> input) throws Exception {
										Iterator<JSON> jit = input.iterator();
										Pair<JSON, JSON> p = new Pair<JSON, JSON>(jit.next(), jit.next());
										return immediateCheckedFuture(p);
									}

								}));
							}
							return transform(allAsList(ll), new AsyncFunction<List<Pair<JSON,JSON>>, JSON>() {

								@Override
								public ListenableFuture<JSON> apply(
										List<Pair<JSON, JSON>> input)
										throws Exception {
									JSONObject obj = builder.object(null);
									for(Pair<JSON, JSON> pp: input) {
										if(pp.f.isValue()) obj.put(stringValue(pp.f),pp.s);
									}
									return immediateCheckedFuture(obj);
								}
							});
						}
						
						return immediateCheckedFuture(builder.value());
				};
				
			});
		}
		};
	}
	
	public InstructionFuture<JSON> contains() {
		return new AbstractInstructionFuture() {
			
			@Override
			public ListenableFuture<JSON> call(final AsyncExecutionContext<JSON> context,
					final ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> arg = context.getdef("1");
				return transform(allAsList(data,arg.call(context, data)), new AsyncFunction<List<JSON>, JSON>() {
					@Override
					public ListenableFuture<JSON> apply(List<JSON> input)
							throws Exception {
						Iterator<JSON> jit = input.iterator();
						JSON a = jit.next();
						JSON b = jit.next();
						if(a instanceof JSONArray) {
							JSONArray larr = (JSONArray) a;
							return immediateCheckedFuture(builder.value(larr.collection().contains(b)));
						}
						return immediateCheckedFuture(builder.value(false));
					}
				});
			}
		};
	}
	public InstructionFuture<JSON> addInstruction(InstructionFuture<JSON> a,
			InstructionFuture<JSON> b) {
		return dyadic(a, b, new DefaultPolymorphicOperator(builder) {
			@Override
			public Double op(AsyncExecutionContext<JSON> eng, Double l, Double r) {
				return l + r;
			}

			@Override
			public Long op(AsyncExecutionContext<JSON> eng, Long l, Long r) {
				return l + r;
			}

			@Override
			public String op(AsyncExecutionContext<JSON> eng, String l, String r) {
				return l + r;
			}

			@Override
			public JSONArray op(AsyncExecutionContext<JSON> eng, JSONArray l,
					JSONArray r) {
				// Collection<JSON> cc = builder.collection();
				JSONArray arr = builder.array(null);
				// this needs to be a deep clone for the internal referencing to
				// hold.
				int i = 0;
				for (JSON j : l.collection()) {
					arr.add(j);
				}
				for (JSON j : r.collection()) {
					arr.add(j);
				}
				return arr;
			}

			@Override
			public JSONArray op(AsyncExecutionContext<JSON> eng, JSONArray l,
					JSON r) {
				JSONArray arr = builder.array(null);
				// this needs to be a deep clone for the internal referencing to
				// hold.
				int i = 0;
				for (JSON j : l.collection()) {
					arr.add(j);
				}
				arr.add(r);
				return arr;
			}

			@Override
			public JSONObject op(AsyncExecutionContext<JSON> eng, JSONObject l,
					JSONObject r) {
				JSONObject obj = builder.object(null);
				for (Map.Entry<String, JSON> ee : r.map().entrySet()) {
					String k = ee.getKey();
					JSON j = ee.getValue();
					obj.put(k, j);
				}
				for (Map.Entry<String, JSON> ee : l.map().entrySet()) {
					String k = ee.getKey();
					JSON j = ee.getValue();
					obj.put(k, j);
				}
				return obj;
			}
		});
	}

	public InstructionFuture<JSON> subInstruction(InstructionFuture<JSON> a,
			InstructionFuture<JSON> b) {
		return dyadic(a, b, new DefaultPolymorphicOperator(builder) {
			@Override
			public Double op(AsyncExecutionContext<JSON> eng, Double l, Double r) {
				return l - r;
			}

			@Override
			public Long op(AsyncExecutionContext<JSON> eng, Long l, Long r) {
				return l - r;
			}

			@Override
			public String op(AsyncExecutionContext<JSON> eng, String l, String r) {
				int n = l.indexOf(r);
				if (n != -1) {
					StringBuilder b = new StringBuilder(l.substring(0, n));
					b.append(l.substring(n + r.length()));
					return b.toString();
				}
				return r;
			}

			@Override
			public JSONArray op(AsyncExecutionContext<JSON> eng, JSONArray l,
					JSONArray r) {
				JSONArray arr = builder.array(null);
				// this needs to be a deep clone for the internal referencing to
				// hold.
				for (JSON j : l.collection()) {
					if (!r.contains(j)) {
						arr.add(j);
					}
				}
				return arr;
			}

			@Override
			public JSONArray op(AsyncExecutionContext<JSON> eng, JSONArray l,
					JSON r) {
				JSONArray arr = builder.array(null);
				// this needs to be a deep clone for the internal referencing to
				// hold.
				for (JSON j : l.collection()) {
					if (!j.equals(r)) {
						arr.add(j);
					}
				}
				return arr;
			}

			@Override
			public JSONObject op(AsyncExecutionContext<JSON> eng, JSONObject l,
					JSONObject r) {
				JSONObject obj = builder.object(null, l.size() + r.size());
				for (Map.Entry<String, JSON> ee : r.map().entrySet()) {
					String k = ee.getKey();
					JSON j = ee.getValue();
					if (!r.containsKey(k))
						obj.put(k, j);
				}
				return obj;
			}
		});
	}

	public InstructionFuture<JSON> mulInstruction(InstructionFuture<JSON> a,
			InstructionFuture<JSON> b) {
		return dyadic(a, b, new DefaultPolymorphicOperator(builder) {
			@Override
			public Double op(AsyncExecutionContext<JSON> eng, Double l, Double r) {
				return l * r;
			}

			@Override
			public Long op(AsyncExecutionContext<JSON> eng, Long l, Long r) {
				return l * r;
			}

		});

	}

	public InstructionFuture<JSON> divInstruction(InstructionFuture<JSON> a,
			InstructionFuture<JSON> b) {
		return dyadic(a, b, new DefaultPolymorphicOperator(builder) {
			@Override
			public Double op(AsyncExecutionContext<JSON> eng, Double l, Double r) {
				return l / r;
			}

			@Override
			public Long op(AsyncExecutionContext<JSON> eng, Long l, Long r) {
				return l / r;
			}
		});

	}

	public InstructionFuture<JSON> modInstruction(InstructionFuture<JSON> a,
			InstructionFuture<JSON> b) {
		return dyadic(a, b, new DefaultPolymorphicOperator(builder) {
			@Override
			public Double op(AsyncExecutionContext<JSON> eng, Double l, Double r) {
				return l % r;
			}

			@Override
			public Long op(AsyncExecutionContext<JSON> eng, Long l, Long r) {
				return l % r;
			}
		});
	}
	
	public InstructionFuture<JSON> isValue() {
		return isType(null,JSONType.NULL,JSONType.DOUBLE,JSONType.LONG, JSONType.STRING);
	}
	
	public InstructionFuture<JSON> isString() {
		final InstructionFuture<JSON> is = isType(JSONType.STRING);
		return new AbstractInstructionFuture() {
			
			@Override
			public ListenableFuture<JSON> call(AsyncExecutionContext<JSON> context,
					ListenableFuture<JSON> data) throws ExecutionException {
				InstructionFuture<JSON> arg = context.getdef("1");
				if(arg == null) return is.call(context, data);
				return transform(arg.call(context, data),new AsyncFunction<JSON, JSON>() {

					@Override
					public ListenableFuture<JSON> apply(JSON input)
							throws Exception {
						if(input.getType() == JSONType.NULL)
							return immediateCheckedFuture(builder.value(""));
						return immediateCheckedFuture(builder.value(input.toString()));
					}
				});
			}
		};
	}

	public InstructionFuture<JSON> isNumber() {
		return isType(JSONType.LONG,JSONType.DOUBLE);
	}

	public InstructionFuture<JSON> isBoolean() {
		return isType(JSONType.BOOLEAN);
	}
	
	public InstructionFuture<JSON> isNull() {
		return isType(JSONType.NULL);
	}
	
	public InstructionFuture<JSON> isArray() {
		return isType(JSONType.ARRAY,JSONType.FRAME);
	}
	
	public InstructionFuture<JSON> isObject() {
		return isType(JSONType.OBJECT);
	}
	
	protected InstructionFuture<JSON> isType(JSONType... types) {
		return new AbstractInstructionFuture() {
			
			@Override
			public ListenableFuture<JSON> call(AsyncExecutionContext<JSON> context, ListenableFuture<JSON> data)
				throws ExecutionException {
					return transform(data, new AsyncFunction<JSON, JSON>() {
						@Override
						public ListenableFuture<JSON> apply(JSON input) throws Exception {
							boolean res = false;
							JSONType jt=input.getType();
							for(JSONType t: types) {
								if(jt.equals(t)) {
									res= true;
									break;
								}
							}
							return immediateCheckedFuture(builder.value(res));
						}
					});
			}
		};
	}

}