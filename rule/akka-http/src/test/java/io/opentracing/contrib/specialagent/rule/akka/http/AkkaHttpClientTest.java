/* Copyright 2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentracing.contrib.specialagent.rule.akkahttp;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import io.opentracing.contrib.specialagent.AgentRunner;
import io.opentracing.contrib.specialagent.AgentRunner.Config;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

@RunWith(AgentRunner.class)
@Config(isolateClassLoader = false)
public class AkkaHttpClientTest {
  private static ActorSystem system;

  @BeforeClass
  public static void beforeClass() {
    system = ActorSystem.create();
  }

  @AfterClass
  public static void afterClass() throws Exception {
    if (system != null)
      Await.result(system.terminate(), getDefaultDuration());
  }

  @Before
  public void before(final MockTracer tracer) {
    tracer.reset();
  }

  @Test
  public void test(final MockTracer tracer) throws Exception {
    final Materializer materializer = ActorMaterializer.create(system);

    Http http = null;
    // Use Reflection to call Http.get(system) because Scala Http class decompiles to java class with 2 similar methods 'Http.get(system)' with difference in return type only
    for (Method method : Http.class.getMethods()) {
      if (method.getName().equals("get") && method.getReturnType().equals(Http.class)) {
        http = (Http) method.invoke(null, system);
        break;
      }
    }

    final CompletionStage<HttpResponse> stage = http.singleRequest(HttpRequest.GET("http://localhost:12345"));

    try {
      stage
          .whenComplete(new BiConsumer<HttpResponse, Throwable>() {
            @Override
            public void accept(HttpResponse httpResponse, Throwable throwable) {
              System.out.println(httpResponse.status());
            }
          }).toCompletableFuture().get().entity().getDataBytes().runForeach(param -> {
      }, materializer);
    } catch (Exception ignore) {
    }

    await().atMost(15, TimeUnit.SECONDS).until(() -> tracer.finishedSpans().size(), equalTo(1));

    final List<MockSpan> spans = tracer.finishedSpans();
    assertEquals(1, spans.size());
    assertEquals(AkkaAgentIntercept.COMPONENT_NAME, spans.get(0).tags().get(Tags.COMPONENT.getKey()));
  }

  private static FiniteDuration getDefaultDuration() {
    return Duration.create(15, "seconds");
  }
}