package ro.filegrid.security;

import org.springframework.stereotype.Component;

/** Placeholder until authentication exists: every request is made by "demo-user". */
@Component
public class DemoCurrentActorProvider implements CurrentActorProvider {

    public static final String DEMO_USER = "demo-user";

    @Override
    public String currentActor() {
        return DEMO_USER;
    }
}
