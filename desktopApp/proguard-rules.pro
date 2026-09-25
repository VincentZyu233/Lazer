# Ktor discovers the CIO engine from META-INF/services at runtime. Compose's release
# shrinker preserves that resource but cannot infer the provider class is reachable.
-keep class io.ktor.client.engine.cio.CIOEngineContainer { *; }
