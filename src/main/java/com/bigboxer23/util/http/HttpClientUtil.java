package com.bigboxer23.util.http;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;

import javax.net.ssl.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 *
 */
public class HttpClientUtil extends TimeoutEnabledHttpClient
{
	public static DefaultHttpClient getSSLDisabledHttpClient()
	{
		TimeoutEnabledHttpClient anInstance = new TimeoutEnabledHttpClient();
		try
			{
				SSLContext anSSLContext = SSLContext.getInstance("SSL");
				X509TrustManager aTrustManager = new X509TrustManager()
				{
					public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException
					{ }

					public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException { }

					public X509Certificate[] getAcceptedIssuers()
					{
						return null;
					}
				};
				anSSLContext.init(null, new TrustManager[]{aTrustManager}, null);
				HostnameVerifier allHostsValid = new HostnameVerifier() {
					public boolean verify(String hostname, SSLSession session) {
						return true;
					}
				};
				HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
				HttpsURLConnection.setDefaultSSLSocketFactory(anSSLContext.getSocketFactory());
				SSLSocketFactory anSSLSockFactory = new SSLSocketFactory(anSSLContext);
				anSSLSockFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
				ClientConnectionManager aClientConnectionManager = anInstance.getConnectionManager();
				SchemeRegistry aSchemeRegistry = aClientConnectionManager.getSchemeRegistry();
				aSchemeRegistry.register(new Scheme("https", anSSLSockFactory, 443));
				return new TimeoutEnabledHttpClient(aClientConnectionManager);
			} catch (Exception ex)
			{
			}
		return anInstance;
	}
}
