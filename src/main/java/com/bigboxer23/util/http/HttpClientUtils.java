package com.bigboxer23.util.http;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 *
 */
public class HttpClientUtils
{
	private static final Logger myLogger = LoggerFactory.getLogger(HttpClientUtils.class);

	private static CloseableHttpClient myHttpClient;

	/**
	 * Remove the cached client
	 */
	public static void reset()
	{
		try
		{
			myHttpClient.close();
		}
		catch (IOException theE)
		{
			myLogger.error("HttpClientUtils:reset", theE);
		}
		myHttpClient = null;
	}

	/**
	 * Return a cached http client which has good default timeouts, and ignores self signed SSL certs for
	 * internal HTTPS
	 *
	 * @return
	 */
	public static CloseableHttpClient getInstance()
	{
		if (myHttpClient != null)
		{
			return myHttpClient;
		}
		try
		{
			myHttpClient = HttpClients
					.custom()
					.setDefaultRequestConfig(RequestConfig.custom()
							.setConnectTimeout(5000)
							.setConnectionRequestTimeout(5000)
							.setSocketTimeout(5000).build())
					.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
					.setSSLContext(new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy()
					{
						public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException
						{
							return true;
						}
					}).build())
					.build();
		}
		catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException theE)
		{
			myLogger.error("HttpClientUtils:getInstance", theE);
		}
		return myHttpClient;
	}
}
