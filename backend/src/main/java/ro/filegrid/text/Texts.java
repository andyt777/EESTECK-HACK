package ro.filegrid.text;

import java.util.Locale;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * Romanian texts produced by the backend (explanations of Agent 01, validation
 * messages shown to the administrator). They live in one bundle,
 * {@code i18n/texts_ro.properties}, so that another language can be added
 * without touching the code.
 *
 * <p>Placeholders are {@code {0}}, {@code {1}}, ... Arguments are inserted as
 * text: callers format numbers and sizes themselves.
 */
@Component
public class Texts {

    private static final Locale ROMANIAN = Locale.forLanguageTag("ro");

    private final ResourceBundleMessageSource source = new ResourceBundleMessageSource();

    public Texts() {
        source.setBasename("i18n/texts");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setAlwaysUseMessageFormat(true);
    }

    public String get(String code, Object... args) {
        Object[] asText = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            asText[i] = String.valueOf(args[i]);
        }
        return source.getMessage(code, asText, ROMANIAN);
    }
}
