/*
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
package io.trino.plugin.hive.metastore.thrift;

import io.airlift.testing.Closeables;
import io.airlift.units.Duration;
import io.opentelemetry.api.OpenTelemetry;
import io.trino.hive.thrift.metastore.Database;
import io.trino.hive.thrift.metastore.NoSuchObjectException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.http.HttpHeaders;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestThriftHttpMetastoreClient
{
    private static final String HTTP_TOKEN = "test-token";
    private static final HiveMetastoreAuthentication NO_HIVE_METASTORE_AUTHENTICATION = new NoHiveMetastoreAuthentication();
    private static final Duration TIMEOUT = new Duration(20, SECONDS);
    private static InMemoryThriftMetastore delegate;
    private static TestingThriftHttpMetastoreServer metastoreServer;

    @BeforeClass(alwaysRun = true)
    public static void setup()
            throws Exception
    {
        TestRequestHeaderInterceptor requestHeaderInterceptor = new TestRequestHeaderInterceptor();
        File tempDir = Files.createTempDirectory(null).toFile();
        tempDir.deleteOnExit();
        delegate = new InMemoryThriftMetastore(new File(tempDir, "/metastore"), new ThriftMetastoreConfig());
        metastoreServer = new TestingThriftHttpMetastoreServer(delegate, requestHeaderInterceptor);
    }

    @AfterClass(alwaysRun = true)
    public static void tearDown()
            throws Exception
    {
        Closeables.closeAll(metastoreServer);
        metastoreServer = null;
    }

    @Test
    public void testHttpThriftConnection()
            throws Exception
    {
        String testDbName = "testdb";
        Database db = new Database().setName(testDbName);
        delegate.createDatabase(db);

        ThriftMetastoreClientFactory factory = new DefaultThriftMetastoreClientFactory(
                Optional.empty(),
                Optional.empty(),
                TIMEOUT,
                NO_HIVE_METASTORE_AUTHENTICATION,
                "localhost",
                DefaultThriftMetastoreClientFactory.buildThriftHttpContext(getHttpMetastoreConfig()),
                OpenTelemetry.noop());
        URI metastoreUri = URI.create("http://localhost:" + metastoreServer.getPort());
        ThriftMetastoreClient client = factory.create(
                metastoreUri, Optional.empty());
        assertThat(client.getAllDatabases()).containsAll(List.of(testDbName));
        // negative case
        assertThatThrownBy(() -> client.getDatabase("does-not-exist"))
                .isInstanceOf(NoSuchObjectException.class);
    }

    private static ThriftHttpMetastoreConfig getHttpMetastoreConfig()
    {
        ThriftHttpMetastoreConfig config = new ThriftHttpMetastoreConfig();
        config.setAdditionalHeaders("key1:value1, key2:value2");
        return config;
    }

    private static class TestRequestHeaderInterceptor
            implements Consumer<HttpServletRequest>
    {
        @Override
        public void accept(HttpServletRequest httpServletRequest)
        {
            assertThat(Collections.list(httpServletRequest.getHeaderNames())).contains("key1", "key2");
            assertThat(httpServletRequest.getHeader("key1")).isEqualTo("value1");
            assertThat(httpServletRequest.getHeader("key2")).isEqualTo("value2");
            assertThat(httpServletRequest.getHeader(HttpHeaders.AUTHORIZATION)).isNull();
        }
    }
}
