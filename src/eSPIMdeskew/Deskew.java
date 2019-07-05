/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eSPIMdeskew;

import org.micromanager.PropertyMap;
import org.micromanager.Studio;
import org.micromanager.data.ProcessorConfigurator;
import org.micromanager.data.ProcessorFactory;
import org.micromanager.data.ProcessorPlugin;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.SciJavaPlugin;
/**
 *
 * @author Yina
 */

@Plugin(type = ProcessorPlugin.class)
public class Deskew implements ProcessorPlugin, SciJavaPlugin{
    private Studio studio_;
    DeskewConfigurator MyConfigurator;
    
    //methods in the dll
    /*public native static void deskew_GPU(short inBuf[], int nx, int ny, int nz,
				double deskewFactor, short outBuf[],
                                int newNx, int extraShift);
    public native static void GPUdevice();*/
    
    @Override
    public ProcessorConfigurator createConfigurator(PropertyMap settings) {
 
        //System.loadLibrary ("DeskewDLL"); // load dll
        
        MyConfigurator = new DeskewConfigurator(studio_, settings);
        
        return MyConfigurator;
    }

    @Override
    public ProcessorFactory createFactory(PropertyMap settings) {
        return new DeskewFactory(MyConfigurator, studio_);
    }

    @Override
    public void setContext(Studio studio) {
        studio_=studio;
    }

    @Override
    public String getName() {
        return "eSPIM deskew";
    }

    @Override
    public String getHelpText() {
        return "Deskew eSPIM data during acquisition";
    }

    @Override
    public String getVersion() {
        return "1";
        
        }

    @Override
    public String getCopyright() {
        return "UCSF, 2019";

    }
    
}
