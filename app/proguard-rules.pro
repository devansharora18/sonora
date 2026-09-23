# Rules for R8, which only runs on a release build.
#
# Deliberately empty of app-specific rules. Everything the app leans on ships its own: the Compose
# compiler generates code that R8 can follow, kotlinx.serialization emits serializers at compile
# time and its runtime carries the consumer rules that keep them, and Media3 does the same. A rule
# added here without a failure to point at is a rule that will outlive its reason.
