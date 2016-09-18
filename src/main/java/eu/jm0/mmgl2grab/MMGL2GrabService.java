package eu.jm0.mmgl2grab;

import java.io.InputStream;

public interface MMGL2GrabService {
	public boolean isReady();
	public void waitTillReady(long timeout);
	public InputStream openCountryDatabase();
	public void closeCountryDatabase(InputStream is);
}
