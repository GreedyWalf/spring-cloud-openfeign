/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.openfeign.ribbon;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;

import com.netflix.client.DefaultLoadBalancerRetryHandler;
import com.netflix.client.RequestSpecificRetryHandler;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;
import feign.Client;
import feign.Request;
import feign.Response;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancedRetryPolicy;
import org.springframework.cloud.client.loadbalancer.ServiceInstanceChooser;
import org.springframework.cloud.netflix.ribbon.DefaultServerIntrospector;
import org.springframework.cloud.netflix.ribbon.RibbonLoadBalancedRetryFactory;
import org.springframework.cloud.netflix.ribbon.RibbonLoadBalancedRetryPolicy;
import org.springframework.cloud.netflix.ribbon.RibbonLoadBalancerContext;
import org.springframework.cloud.netflix.ribbon.ServerIntrospector;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.TerminatedRetryException;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;

import static com.netflix.client.config.CommonClientConfigKey.ConnectTimeout;
import static com.netflix.client.config.CommonClientConfigKey.MaxAutoRetries;
import static com.netflix.client.config.CommonClientConfigKey.MaxAutoRetriesNextServer;
import static com.netflix.client.config.CommonClientConfigKey.OkToRetryOnAllOperations;
import static com.netflix.client.config.CommonClientConfigKey.ReadTimeout;
import static com.netflix.client.config.DefaultClientConfigImpl.DEFAULT_MAX_AUTO_RETRIES;
import static com.netflix.client.config.DefaultClientConfigImpl.DEFAULT_MAX_AUTO_RETRIES_NEXT_SERVER;
import static feign.Request.HttpMethod.GET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Ryan Baxter
 * @author Gang Li
 */
public class RetryableFeignLoadBalancerTests {
	@Mock
	private ILoadBalancer lb;
	@Mock
	private IClientConfig config;
	private ServerIntrospector inspector = new DefaultServerIntrospector();

	private Integer defaultConnectTimeout = 10000;
	private Integer defaultReadTimeout = 10000;

	@Before
	public void setup() {
		MockitoAnnotations.initMocks(this);
		when(this.config.get(MaxAutoRetries, DEFAULT_MAX_AUTO_RETRIES)).thenReturn(1);
		when(this.config.get(MaxAutoRetriesNextServer,
				DEFAULT_MAX_AUTO_RETRIES_NEXT_SERVER)).thenReturn(1);
		when(this.config.get(OkToRetryOnAllOperations, eq(anyBoolean())))
				.thenReturn(true);
		when(this.config.get(ConnectTimeout)).thenReturn(this.defaultConnectTimeout);
		when(this.config.get(ReadTimeout)).thenReturn(this.defaultReadTimeout);
		when(this.config.get(OkToRetryOnAllOperations, false)).thenReturn(true);
	}

	@Test
	public void executeNoFailure() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("404,502,foo, ,").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory);
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response response = Response.builder()
				.status(200)
				.request(feignRequest)
				.headers(new HashMap<>())
				.build();
		doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		assertEquals(200, ribbonResponse.toResponse().status());
		verify(client, times(1)).execute(any(Request.class), any(Request.Options.class));
	}

	@Test
	public void executeNeverRetry() throws Exception {
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		doThrow(new IOException("boom")).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, new LoadBalancedRetryFactory() {
			@Override
			public LoadBalancedRetryPolicy createRetryPolicy(String s, ServiceInstanceChooser serviceInstanceChooser) {
				return null;
			}

			@Override
			public RetryListener[] createRetryListeners(String service) {
				return new RetryListener[0];
			}

			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return null;
			}
		});
		try {
			feignLb.execute(request, null);
		} catch(Exception e) {
			assertThat(e, instanceOf(IOException.class));
		} finally {
			verify(client, times(1)).execute(any(Request.class), any(Request.Options.class));
		}
	}

	@Test
	public void executeRetry() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response response = Response.builder()
				.status(200)
				.request(feignRequest)
				.headers(new HashMap<>())
				.build();
		doThrow(new IOException("boom")).doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));

		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		assertEquals(200, ribbonResponse.toResponse().status());
		verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
		assertEquals(1, backOffPolicy.getCount());
	}

	@Test
	public void executeRetryOnStatusCode() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("404").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response response = Response.builder().status(200).headers(new HashMap<>())
				.build();
		Response fourOFourResponse = Response.builder().status(404)
				.headers(new HashMap<>()).build();
		doReturn(fourOFourResponse).doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		assertEquals(200, ribbonResponse.toResponse().status());
		verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
		assertEquals(1, backOffPolicy.getCount());
	}

		@Test
	public void executeRetryOnStatusCodeWithEmptyBody() throws Exception {
			int retriesNextServer = 0;
			when(this.config.get(MaxAutoRetriesNextServer,
							DEFAULT_MAX_AUTO_RETRIES_NEXT_SERVER)).thenReturn(retriesNextServer);
			doReturn(new Server("foo", 80)).when(lb).chooseServer(any());
			RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
			SpringClientFactory clientFactory = mock(SpringClientFactory.class);
			IClientConfig config = mock(IClientConfig.class);
			doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
			doReturn(retriesNextServer).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
			doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
			doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
			doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
			doReturn("404").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
			doReturn(config).when(clientFactory).getClientConfig(eq("default"));
			doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
			MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
			RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
				@Override
				public BackOffPolicy createBackOffPolicy(String service) {
					return backOffPolicy;
				}
			};
			Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
					new byte[] {}, UTF_8);
			Client client = mock(Client.class);
			FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
			Response response = Response.builder().status(404).headers(new HashMap<>())
					.build();
			Response fourOFourResponse = Response.builder().status(404)
					.headers(new HashMap<>()).build();
			doReturn(fourOFourResponse).doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
			RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
			FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
			assertEquals(404, ribbonResponse.toResponse().status());
			assertEquals(Integer.valueOf(0), ribbonResponse.toResponse().body().length());
			verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
			assertEquals(1, backOffPolicy.getCount());
	}

	@Test
	public void getRequestSpecificRetryHandler() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory);
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response response = Response.builder().status(200).headers(new HashMap<>())
				.build();
		doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		RequestSpecificRetryHandler retryHandler = feignLb.getRequestSpecificRetryHandler(request, config);
		assertEquals(1, retryHandler.getMaxRetriesOnNextServer());
		assertEquals(1, retryHandler.getMaxRetriesOnSameServer());

	}

	@Test
	public void choose() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory);
		Request feignRequest = Request
				.create(GET, "http://foo", new HashMap<>(),
						new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response response = Response.builder()
				.request(feignRequest)
				.status(200).headers(new HashMap<>())
				.build();
		doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
		final Server server = new Server("foo", 80);
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(new ILoadBalancer() {
			@Override
			public void addServers(List<Server> list) {

			}

			@Override
			public Server chooseServer(Object o) {
				return server;
			}

			@Override
			public void markServerDown(Server server) {

			}

			@Override
			public List<Server> getServerList(boolean b) {
				return null;
			}

			@Override
			public List<Server> getReachableServers() {
				return null;
			}

			@Override
			public List<Server> getAllServers() {
				return null;
			}
		}, config, inspector, loadBalancedRetryFactory);
		ServiceInstance serviceInstance = feignLb.choose("foo");
		assertEquals("foo", serviceInstance.getHost());
		assertEquals(80, serviceInstance.getPort());

	}

	@Test
	public void retryListenerTest() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		MyRetryListener myRetryListener = new MyRetryListener();
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory) {
			@Override
			public RetryListener[] createRetryListeners(String service) {
				return new RetryListener[]{myRetryListener};
			}

			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://listener", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://listener"));
		Response response = Response.builder()
				.request(feignRequest)
				.status(200)
				.headers(new HashMap<>())
				.build();
		doThrow(new IOException("boom")).doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));

		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		assertEquals(200, ribbonResponse.toResponse().status());
		verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
		assertEquals(1, backOffPolicy.getCount());
		assertEquals(1, myRetryListener.getOnError());
	}

	@Test(expected = TerminatedRetryException.class)
	public void retryListenerTestNoRetry() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		Response response = Response.builder().status(200).headers(new HashMap<>())
				.build();
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		MyRetryListenerNotRetry myRetryListenerNotRetry = new MyRetryListenerNotRetry();
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
			@Override
			public RetryListener[] createRetryListeners(String service) {
				return new RetryListener[]{myRetryListenerNotRetry};
			}

			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://listener", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://listener"));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
	}

	@Test
	public void retryWithDefaultConstructorTest() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES),eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		RibbonLoadBalancedRetryFactory loadBalancedRetryPolicyFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://listener", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://listener"));
		Response response = Response.builder().status(200).headers(new HashMap<>())
				.build();
		doThrow(new IOException("boom")).doReturn(response).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryPolicyFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		assertEquals(200, ribbonResponse.toResponse().status());
		verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
		assertEquals(1, backOffPolicy.getCount());
	}

	@Test
	public void executeRetryFail() throws Exception {
		RibbonLoadBalancerContext lbContext = new RibbonLoadBalancerContext(lb, config);
		lbContext.setRetryHandler(new DefaultLoadBalancerRetryHandler(1, 0, true));
		SpringClientFactory clientFactory = mock(SpringClientFactory.class);
		IClientConfig config = mock(IClientConfig.class);
		doReturn(1).when(config).get(eq(CommonClientConfigKey.MaxAutoRetries), anyInt());
		doReturn(0).when(config).get(eq(CommonClientConfigKey.MaxAutoRetriesNextServer), anyInt());
		doReturn(true).when(config).get(eq(CommonClientConfigKey.OkToRetryOnAllOperations), eq(false));
		doReturn(defaultConnectTimeout).when(config).get(eq(CommonClientConfigKey.ConnectTimeout));
		doReturn(defaultReadTimeout).when(config).get(eq(CommonClientConfigKey.ReadTimeout));
		doReturn("404").when(config).getPropertyAsString(eq(RibbonLoadBalancedRetryPolicy.RETRYABLE_STATUS_CODES), eq(""));
		doReturn(config).when(clientFactory).getClientConfig(eq("default"));
		doReturn(lbContext).when(clientFactory).getLoadBalancerContext(any(String.class));
		MyBackOffPolicy backOffPolicy = new MyBackOffPolicy();
		RibbonLoadBalancedRetryFactory loadBalancedRetryFactory = new RibbonLoadBalancedRetryFactory(clientFactory){
			@Override
			public BackOffPolicy createBackOffPolicy(String service) {
				return backOffPolicy;
			}
		};
		Request feignRequest = Request.create(GET, "http://foo", new HashMap<>(),
				new byte[] {}, UTF_8);
		Client client = mock(Client.class);
		FeignLoadBalancer.RibbonRequest request = new FeignLoadBalancer.RibbonRequest(client, feignRequest, new URI("http://foo"));
		Response fourOFourResponse = Response.builder().status(404)
				.headers(new HashMap<>())
				.body(new Response.Body() { //set content into response
					@Override
					public Integer length() {
						return "test".getBytes().length;
					}

					@Override
					public boolean isRepeatable() {
						return true;
					}

					@Override
					public InputStream asInputStream() throws IOException {
						return new ByteArrayInputStream("test".getBytes());
					}

					@Override
					public Reader asReader() throws IOException {
						return new InputStreamReader(asInputStream(), UTF_8);
					}

					@Override
					public Reader asReader(Charset charset) throws IOException {
						return new InputStreamReader(asInputStream(), charset);
					}

					@Override
					public void close() throws IOException {
					}
				}).build();
		doReturn(fourOFourResponse).when(client).execute(any(Request.class), any(Request.Options.class));
		RetryableFeignLoadBalancer feignLb = new RetryableFeignLoadBalancer(lb, config, inspector, loadBalancedRetryFactory);
		FeignLoadBalancer.RibbonResponse ribbonResponse = feignLb.execute(request, null);
		verify(client, times(2)).execute(any(Request.class), any(Request.Options.class));
		assertEquals(1, backOffPolicy.getCount());
		InputStream inputStream = ribbonResponse.toResponse().body().asInputStream();
		byte[] buf = new byte[100];
		int read = inputStream.read(buf);
		Assert.assertThat(new String(buf, 0, read), is("test"));
	}

	class MyBackOffPolicy implements BackOffPolicy {

		private int count = 0;

		@Override
		public BackOffContext start(RetryContext retryContext) {
			return null;
		}

		@Override
		public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
			count++;
		}

		int getCount() {
			return count;
		}

	}

	class MyRetryListener implements RetryListener {

		private int onError = 0;

		@Override
		public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
			return true;
		}

		@Override
		public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {

		}

		@Override
		public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
			onError++;
		}

		int getOnError() {
			return onError;
		}
	}

	class MyRetryListenerNotRetry implements RetryListener {

		@Override
		public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
			return false;
		}

		@Override
		public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {

		}

		@Override
		public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {}
	}

}