/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eSPIMdeskew;

import org.micromanager.Studio;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorFactory;
/**
 *
 * @author Yina
 */
public class DeskewFactory implements ProcessorFactory{
    Studio studio_;
    DeskewConfigurator MyConfigurator;

    public DeskewFactory(DeskewConfigurator iConfigurator, Studio studio)
    {
        studio_=studio;
        MyConfigurator=iConfigurator;
    }
    
    @Override
    public Processor createProcessor() {
        return new DeskewProcessor(studio_, MyConfigurator);
    }
}
