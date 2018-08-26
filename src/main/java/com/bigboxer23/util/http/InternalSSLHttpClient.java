package com.bigboxer23.util.http;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;

import javax.net.ssl.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 *
 */
public class InternalSSLHttpClient extends TimeoutEnabledHttpClient
{
	@Override
	protected ClientConnectionManager createClientConnectionManager()
	{
		try
		{
			SSLContext anSSLContext = SSLContext.getInstance("SSL");
			X509TrustManager aTrustManager = new X509TrustManager()
			{
				@Override
				public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException
				{ }

				@Override
				public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException { }

				@Override
				public X509Certificate[] getAcceptedIssuers()
				{
					return null;
				}
			};
			anSSLContext.init(null, new TrustManager[]{aTrustManager}, null);
			HostnameVerifier allHostsValid = (hostname, session) -> true;
			HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
			HttpsURLConnection.setDefaultSSLSocketFactory(anSSLContext.getSocketFactory());
			org.apache.http.conn.ssl.SSLSocketFactory anSSLSockFactory = new org.apache.http.conn.ssl.SSLSocketFactory(anSSLContext);
			anSSLSockFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
			ClientConnectionManager aClientConnectionManager = super.createClientConnectionManager();
			SchemeRegistry aSchemeRegistry = aClientConnectionManager.getSchemeRegistry();
			aSchemeRegistry.register(new Scheme("https", anSSLSockFactory, 443));
			return aClientConnectionManager;
		} catch (Exception ex)
		{
		}
		return null;
	}
}
