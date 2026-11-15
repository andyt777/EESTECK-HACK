package ro.filegrid.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ro.filegrid.incident.IncidentService;
import ro.filegrid.incident.NotificationChannel;
import ro.filegrid.incident.NotificationRouting;
import ro.filegrid.text.Texts;
import ro.filegrid.upload.FileIntakeService;

/** Wiring of PPCastor on WhatsApp (api profile only). */
@Configuration
@Profile("api")
public class WhatsAppConfiguration {

    @Bean
    WhatsAppContacts whatsAppContacts(ConfigurableEnvironment environment) {
        return WhatsAppContacts.from(environment);
    }

    @Bean
    IntelligenceClient intelligenceClient(WhatsAppProperties props, ObjectMapper json) {
        return new IntelligenceClient(props, json);
    }

    @Bean
    TwilioMedia twilioMedia(ConfigurableEnvironment environment) {
        return new TwilioMedia(environment.getProperty("TWILIO_ACCOUNT_SID", ""),
                environment.getProperty("TWILIO_AUTH_TOKEN", ""));
    }

    @Bean
    WhatsAppService whatsAppService(NamedParameterJdbcTemplate jdbc, IncidentService incidents,
                                    NotificationRouting routing, NotificationChannel channel,
                                    FileIntakeService intake, IntelligenceClient intelligence, TwilioMedia media,
                                    WhatsAppContacts contacts, WhatsAppProperties props, Texts texts,
                                    ObjectMapper json) {
        return new WhatsAppService(jdbc, incidents, routing, channel, intake, intelligence, media, contacts, props,
                texts, json);
    }
}
