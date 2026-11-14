package ro.filegrid.security;

/**
 * Who is performing the current operation. Authentication arrives in a later
 * stage; every place that needs the actor already goes through this interface.
 */
public interface CurrentActorProvider {

    String currentActor();
}
