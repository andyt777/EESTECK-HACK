# simulator

Simulatorul de contoare inteligente și `topology.yaml` (Etapa 5).

Autentificare (Etapa 11): token client_credentials pentru clientul Keycloak `filegrid-simulator` (`KC_SIMULATOR_CLIENT_ID`, `KC_SIMULATOR_SECRET`, `KC_TOKEN_URL`), reîmprospătat cu 30 s înainte de expirare, un retry pe 401. Fără secret, citirile se trimit fără token.
