package gg.auroramc.aurora.expansions.placeholder;

import gg.auroramc.aurora.api.dependency.Dep;
import gg.auroramc.aurora.api.dependency.DependencyManager;
import gg.auroramc.aurora.api.expansions.AuroraExpansion;
import gg.auroramc.aurora.api.placeholder.PlaceholderHandlerRegistry;

public class PlaceholderExpansion implements AuroraExpansion {
    private AuroraPapiExpansion expansion;
    @Override
    public void hook() {
        expansion = new AuroraPapiExpansion();
        expansion.register();
        PlaceholderHandlerRegistry.addHandler(new MetaHandler());
        PlaceholderHandlerRegistry.addHandler(new ColorHandler());
        PlaceholderHandlerRegistry.addHandler(new LangHandler());

        if (DependencyManager.hasDep(Dep.WORLDGUARD)) {
            PlaceholderHandlerRegistry.addHandler(new InRegionHandler());
        }
    }

    @Override
    public boolean canHook() {
        return DependencyManager.hasDep(Dep.PAPI);
    }

    public void dispose() {
        if (expansion != null) { expansion.unregister(); expansion = null; }
    }
}
