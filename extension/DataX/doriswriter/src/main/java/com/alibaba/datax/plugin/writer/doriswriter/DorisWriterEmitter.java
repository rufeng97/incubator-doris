/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
  -->
 */
package com.alibaba.datax.plugin.writer.doriswriter;

import com.alibaba.fastjson.JSON;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

public class DorisWriterEmitter {

	private static final Logger LOG = LoggerFactory.getLogger(DorisWriterEmitter.class);
	;
	private final Key keys;
	private int pos;


	public DorisWriterEmitter(final Key keys) {
		this.keys = keys;
	}


	/**
	 * execute doris stream load
	 */
	public void doStreamLoad(final DorisFlushBatch flushData) throws IOException {
		final String host = this.getAvailableHost();
		if (null == host) {
			throw new IOException("None of the host in `beLoadUrl` could be connected.");
		}
		final String loadUrl = host + "/api/" + this.keys.getDatabase() + "/" + this.keys.getTable() + "/_stream_load";
		LOG.info(String.format("Start to join batch data: rows[%d] bytes[%d] label[%s].", flushData.getRows().size(), flushData.getBytes(), flushData.getLabel()));
		// do http put request
		final Map<String, Object> loadResult = this.doHttpPut(loadUrl, flushData.getLabel(), this.mergeRows(flushData.getRows()));
		// get response
		final String keyStatus = "Status";
		if (null == loadResult || !loadResult.containsKey(keyStatus)) {
			throw new IOException("Unable to flush data to doris: unknown result status.");
		}
		LOG.info("StreamLoad response:\n" + JSON.toJSONString(loadResult));
		if (loadResult.get(keyStatus).equals("Fail")) {
			throw new IOException("Failed to flush data to doris.\n" + JSON.toJSONString(loadResult));
		}
	}

	/**
	 * loop to get  be host
	 * @return
	 */
	private String getAvailableHost() {
		final List<String> hostList = this.keys.getBeLoadUrlList();
		if (this.pos >= hostList.size()) {
			this.pos = 0;
		}
		while (this.pos < hostList.size()) {
			final String host = "http://" + hostList.get(this.pos);
			if (this.tryHttpConnection(host)) {
				return host;
			}
			++this.pos;
		}
		return null;
	}

	private boolean tryHttpConnection(final String host) {
		try {
			final URL url = new URL(host);
			final HttpURLConnection co = (HttpURLConnection) url.openConnection();
			co.setConnectTimeout(1000);
			co.connect();
			co.disconnect();
			return true;
		} catch (Exception e) {
			LOG.warn("Failed to connect to address:{} , Exception ={}", host, e);
			return false;
		}
	}

	private byte[] mergeRows(final List<String> rows) {
		final StringJoiner stringJoiner = new StringJoiner(",", "[", "]");
		for (final String row : rows) {
			stringJoiner.add(row);
		}
		return stringJoiner.toString().getBytes(StandardCharsets.UTF_8);
	}

	private Map<String, Object> doHttpPut(final String loadUrl, final String label, final byte[] data) throws IOException {
		LOG.info(String.format("Executing stream load to: '%s', size: '%s'", loadUrl, data.length));
		final HttpClientBuilder httpClientBuilder = HttpClients.custom().setRedirectStrategy(new DefaultRedirectStrategy() {
			@Override
			protected boolean isRedirectable(final String method) {
				return true;
			}
		});
		try (final CloseableHttpClient httpclient = httpClientBuilder.build()) {
			final HttpPut httpPut = new HttpPut(loadUrl);
			final List<String> cols = this.keys.getColumns();
			if (null != cols && !cols.isEmpty()) {
				httpPut.setHeader("columns", String.join(",", cols));
			}
			// put loadProps to http header
			final Map<String, Object> loadProps = this.keys.getLoadProps();
			if (null != loadProps) {
				for (final Map.Entry<String, Object> entry : loadProps.entrySet()) {
					httpPut.setHeader(entry.getKey(), String.valueOf(entry.getValue()));
				}
			}
			httpPut.setHeader("Expect", "100-continue");
			httpPut.setHeader("label", label);
			httpPut.setHeader("Content-Type", "application/x-www-form-urlencoded");
			httpPut.setHeader("Authorization", this.getBasicAuthHeader(this.keys.getUsername(), this.keys.getPassword()));
			httpPut.setHeader("format", "json");
			httpPut.setHeader("strip_outer_array", "true");
			httpPut.setEntity(new ByteArrayEntity(data));
			httpPut.setConfig(RequestConfig.custom().setRedirectsEnabled(true).build());
			try (final CloseableHttpResponse resp = httpclient.execute(httpPut)) {
				final int code = resp.getStatusLine().getStatusCode();
				if (HttpStatus.SC_OK != code) {
					LOG.warn("Request failed with code:{}", code);
					return null;
				}
				final HttpEntity respEntity = resp.getEntity();
				if (null == respEntity) {
					LOG.warn("Request failed with empty response.");
					return null;
				}
				return (Map<String, Object>) JSON.parse(EntityUtils.toString(respEntity));
			}
		}
	}

	private String getBasicAuthHeader(final String username, final String password) {
		final String auth = username + ":" + password;
		final byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
		return "Basic " + new String(encodedAuth);
	}


}