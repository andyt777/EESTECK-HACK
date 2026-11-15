package ro.filegrid.testsupport;

import java.util.List;
import java.util.Map;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Registered in META-INF/spring.factories for every Spring test: the application validates
 * tokens with the key of {@link TestTokens}, and the auto-configured {@code TestRestTemplate}
 * sends the test administrator's token unless a request sets its own Authorization header.
 */
public class TestSecurityContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return new Customizer();
    }

    static final class Customizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-security", Map.of(
                    "filegrid.security.public-key", TestTokens.publicKeyBase64())));
            RestTemplateCustomizer bearer = restTemplate -> restTemplate.getInterceptors().add((request, body, exec) -> {
                if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, TestTokens.bearerAdmin());
                }
                return exec.execute(request, body);
            });
            context.getBeanFactory().registerSingleton("testBearerRestTemplateCustomizer", bearer);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Customizer;
        }

        @Override
        public int hashCode() {
            return Customizer.class.hashCode();
        }
    }
}
