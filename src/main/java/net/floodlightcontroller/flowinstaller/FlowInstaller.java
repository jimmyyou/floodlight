package net.floodlightcontroller.flowinstaller;

import java.util.Collection;
import java.util.Map;

import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.flowinstaller.FlowModSetter;

public class FlowInstaller implements IFloodlightModule {

    protected IOFSwitchService switchService;

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return null;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        this.switchService = context.getServiceImpl(IOFSwitchService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        // Start the FlowModSetter to listen for incoming rules from
        // the GAIA controller and set the rules on switches.
        (new Thread(new FlowModSetter(switchService))).start();

        (new Thread(new BaselineFlowModListener(switchService))).start(); // the thread for baseline emulation
    }
}
