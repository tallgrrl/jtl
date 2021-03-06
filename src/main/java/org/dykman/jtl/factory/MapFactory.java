package org.dykman.jtl.factory;

import java.util.Locale;
import java.util.Map;

public interface MapFactory<T,U> {
	public Map<T,U> createMap();
	public Map<T,U> createMap(Locale locale);
	public Map<T,U> createMap(int c);
	public Map<T,U> copyMap(Map<T,U> rhs);
}
