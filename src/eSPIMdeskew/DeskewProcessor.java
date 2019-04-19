/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eSPIMdeskew_v2;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import mmcorej.CMMCore;
import javax.swing.JOptionPane;


import ij.process.ImageProcessor;

import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorContext;
import org.micromanager.acquisition.SequenceSettings;
import org.micromanager.data.internal.multipagetiff.StorageMultipageTiff;
import org.micromanager.data.Storage;
import org.micromanager.data.Datastore;
import org.micromanager.data.Coords;
import org.micromanager.data.Metadata;
import org.micromanager.display.DisplayWindow;
import org.micromanager.PositionList;
import org.micromanager.MultiStagePosition;
import org.micromanager.StagePosition;


/**
 *
 * @author Yina
 */
public class DeskewProcessor extends Processor{
    Studio studio_;
    CMMCore mmc;
    Datastore deskew;   //for deskewed data storage and display
    DeskewConfigurator configurator_;   //Deskew processing settings
    SequenceSettings seqSettings_;      //get MDA acquisitiosn settings
    volatile int deskewVolumeid = 0;    //counting the number of deskewed volumes
    
    public int imageWidth,imageHight, newDeskewSize;
    public int framePerVolume;
    public int channelNum;
    public int basedChannelNum;
    public int posNumXY;
    public int posNumZ;
    public double deskewfactor;
    static boolean startDeskewDisplay = false;
    public boolean initialFinishFlag = false;
    public boolean saveDeskew;
    public boolean motionCorrectionFlag;
    public String savePath;
    
    public double pixelSize;
    public double[] offsetForUpdate;
    
    public Storage fileSaver;
    public DisplayWindow displayDeskew;
    
    public static int interval_;        //can be changed during acquisition
    
    public DeskewProcessor(Studio studio, DeskewConfigurator MyConfigurator)
    {
        studio_=studio;
        mmc=studio_.getCMMCore();
        configurator_ = MyConfigurator;
        seqSettings_ = studio_.acquisitions().getAcquisitionSettings();
        interval_ = configurator_.getVolumeinterval();
        
        double zstep = configurator_.getZstep();
        double angle = configurator_.getAngle();
        int pixelsize = configurator_.getPixelsize();
        saveDeskew = configurator_.getSaveFileCheckbox();
        motionCorrectionFlag = configurator_.getMotionCorrectionCheckbox();
        
        imageHight = (int) mmc.getImageWidth(); //swap height and width due to 90degree rotation
        imageWidth = (int) mmc.getImageHeight();
        channelNum = (int) seqSettings_.channels.size();
        framePerVolume = (int) seqSettings_.slices.size();
        posNumXY = configurator_.getPosNumXY();
        posNumZ = configurator_.getPosNumZ();
        basedChannelNum = configurator_.getBasedChannel();
        
        offsetForUpdate = new double[2];
        offsetForUpdate[0]=0;
        offsetForUpdate[1]=0;
        
        // calculate parameters for deskew
        deskewfactor = (float)  Math.cos(angle * Math.PI / 180) * zstep / (pixelsize / 1000.0);
        newDeskewSize = (int) Math.ceil(imageWidth + deskewfactor * framePerVolume); 
        
        pixelSize = 0.133;
    }
    
    @Override
    public void processImage(Image image, ProcessorContext pc) {
        //get the coords of the image
        int zIndex = image.getCoords().getZ();
        int xyIndex = image.getCoords().getStagePosition();
        int cIndex = image.getCoords().getChannel();
        int tIndex = image.getCoords().getTimePoint();
        if (zIndex == 0){
            //check interval_ updating at the beginning of each volume
            interval_ = configurator_.getVolumeinterval();
        }
        
        if(deskewRequested(image)){
            //initialize deskew multipage TIFF datastore and display at the beginning
            if(atAcquisitionBeginning(image)){
                if(saveDeskew){
                    savePath = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_deskew";  
                    try {
                        deskew = studio_.data().createMultipageTIFFDatastore(savePath, true, StorageMultipageTiff.getShouldSplitPositions());
                    } catch (IOException ex) {
                        Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }else{
                    deskew = studio_.data().createRAMDatastore();
                }
                displayDeskew = studio_.displays().createDisplay(deskew);
                studio_.displays().manage(deskew);
            }
            
            //add new images to deskew datastore
            Image deskewed = deskewSingleImage(image, framePerVolume, newDeskewSize, (float) deskewfactor);
            try {
                deskew.putImage(deskewed);
            } catch (IOException ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            if (zIndex == (framePerVolume - 1)){
                deskewVolumeid++;
            }
            if(motionCorrectionFlag){
                if(zIndex == ((int)framePerVolume/2)&& (cIndex == basedChannelNum) &&((xyIndex) % posNumZ == 0)){
                    double[] offset = findWeightedCenter(deskewed);
                    offsetForUpdate = offset;
                    //String msg = "To be update: xyIndex = " + Integer.toString(xyIndex) + ",zIndex = " + Integer.toString(zIndex) + ", cIndex = " + Integer.toString(cIndex) + ", Offset: x=" + Double.toString(offset[0]) + ", y=" + Double.toString(offset[1]);
                    //JOptionPane.showMessageDialog(null, msg);
                }
                
                if(zIndex == (framePerVolume-1)&& (cIndex == channelNum-1) &&((xyIndex+1) % posNumZ == 0)){
                    //double[] offset = {0,0};
                    //String msg = "Updating: xyIndex = " + Integer.toString(xyIndex) + ",zIndex = " + Integer.toString(zIndex) + ", cIndex = " + Integer.toString(cIndex)  + ", Offset: x=" + Double.toString(offsetForUpdate[0]) + ", y=" + Double.toString(offsetForUpdate[1]);
                    //JOptionPane.showMessageDialog(null, msg);
                    double tsh = 5;
                    if(Math.abs(offsetForUpdate[0])>tsh || Math.abs(offsetForUpdate[1])>tsh){
                        PositionList pl = studio_.positions().getPositionList();
                        //PositionList pl_new = new PositionList();
                        //int posNum = pl.getNumberOfPositions();
                        //String xyStage = "XY";
                        //String zStage = "Z";
                        String xyStage = configurator_.getXYStageName();
                        String zStage = configurator_.getZStageName();
                        for(int posInd = xyIndex+1-posNumZ;posInd<xyIndex+1;posInd++){
                            MultiStagePosition msp = pl.getPosition(posInd);
                            String lb = msp.getLabel();
                            double xx = msp.get(xyStage).x;
                            double yy = msp.get(xyStage).y;
                            double zz = msp.get(zStage).x;

                            //MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx+0.9*offsetForUpdate[0]*pixelSize, yy-0.9*offsetForUpdate[1]*pixelSize, zStage, zz);
                            //MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx-0.9*offsetForUpdate[1]*pixelSize, yy+0.9*offsetForUpdate[0]*pixelSize, zStage, zz);
                            MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx-0.8*offsetForUpdate[1]*pixelSize, yy-0.8*offsetForUpdate[0]*0.866, zStage, zz+0.8*offsetForUpdate[0]*0.5);
                            msp_new.setLabel(lb);
                            pl.replacePosition(posInd, msp_new);
                        }

                        studio_.positions().setPositionList(pl);
                    }
                    offsetForUpdate[0] = 0;
                    offsetForUpdate[1] = 0;
                }
            }
            
            
        } 
        
        if(atAcquisitionEnd(image) && saveDeskew){
            try {
            deskew.freeze();
            deskew.save(Datastore.SaveMode.MULTIPAGE_TIFF, savePath);
            deskew.close();
            } catch (IOException ex) {
            Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        
        pc.outputImage(image); 
    }
    
    private boolean deskewRequested(Image image){
        int index = image.getCoords().getTimePoint();
        return (index % interval_ == 0);
    }
    
    private boolean atAcquisitionBeginning(Image image){
        if (initialFinishFlag){
            return false;
        }
        int timeIndex = image.getCoords().getTimePoint();
        int zIndex = image.getCoords().getZ();
        if(timeIndex == 0 && zIndex == 0){
            initialFinishFlag = true;
            
            posNumXY = configurator_.getPosNumXY();
            posNumZ = configurator_.getPosNumZ();
          
            saveDeskew = configurator_.getSaveFileCheckbox();
            motionCorrectionFlag = configurator_.getMotionCorrectionCheckbox();
            framePerVolume = (int) seqSettings_.slices.size();
            basedChannelNum = configurator_.getBasedChannel();
            
            return true;
        }
        return false;
    }
    
    private boolean atAcquisitionEnd(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int zIndex = image.getCoords().getZ();
        int xyIndex = image.getCoords().getStagePosition();
        int cIndex = image.getCoords().getChannel();
        
        int timeEnd = (int) seqSettings_.numFrames;
        
        if (timeIndex == (timeEnd-1) && zIndex == (framePerVolume-1) && cIndex == (channelNum-1) && xyIndex == (posNumZ*posNumXY-1)){
            initialFinishFlag = false;
            return true;
        }
        
        return false;
    }
    
    private Image deskewSingleImage(Image image, int framePerVolume, int newDeskewSize, float deskewFactor){
        Image newImage, imageFlip;
        
        //TODO: flip and rotate image using user specific parameters.
        ImageProcessor proc = studio_.data().ij().createProcessor(image);
        proc.flipHorizontal();      //flip and rotate according current Yosemite eSPIM setup
        proc = proc.rotateLeft();
        //proc = proc.rotateRight();
        imageFlip = studio_.data().ij().createImage(proc, image.getCoords(), image.getMetadata());
        
        short[] deskewpixels = new short[imageHight * newDeskewSize];
        short rawpixels[] =(short[]) imageFlip.getRawPixels();//reference to rawpixels of image
        
        Coords.CoordsBuilder coordsBuilder = image.getCoords().copy();               //get coords builder
        Metadata.MetadataBuilder metadataBuilder = image.getMetadata().copy();        //get metadata builder
        
        coordsBuilder.time(image.getCoords().getTimePoint()/interval_);
        Coords coords = coordsBuilder.build();
        Metadata metadata = metadataBuilder.build();
        
        //image pixel translation
        int zout = image.getCoords().getZ();
        int nz = framePerVolume;
        short zeropad = 0;
        
        for(int yout=0; yout < imageHight; yout++){
            for(int xout=0; xout < newDeskewSize; xout++){ 
                float xin = (float) ((xout - newDeskewSize/2.) - deskewFactor*(zout-nz/2.) + imageWidth/2.);
                if (xin >= 0 && xin < imageWidth - 1){
                    int index = yout*imageWidth + (int)Math.floor(xin);
                    float offset = (float) (xin - Math.floor(xin));
                    
                    short weighted = (short)((1-offset)*(int)(rawpixels[index]&0xffff) + offset*(int)(rawpixels[index+1]&0xffff));
                    deskewpixels[yout*newDeskewSize+xout] = weighted;
                }else{
                    deskewpixels[yout*newDeskewSize+xout]=zeropad;
                }
            }
        }
        newImage = studio_.data().createImage(deskewpixels, newDeskewSize, imageHight, 2, 1, coords, metadata);
        return newImage;
    }
    private double[] findWeightedCenter(Image image){
        int heightTmp = image.getHeight();
        int widthTmp = image.getWidth();
        short rawpixels[] =(short[]) image.getRawPixels();
        //String msg1 = "OK 1! Height = " + Integer.toString(heightTmp) + ", width = " + Integer.toString(widthTmp) + ", length = " + Integer.toString(rawpixels.length);
        //JOptionPane.showMessageDialog(null, msg1);
        double xWeightedSum = 0.1;
        double yWeightedSum = 0.1;
        double xSum = 0.01;
        double ySum = 0.01;
        for(int yout=0; yout < heightTmp; yout++){
            for(int xout=0; xout < widthTmp; xout++){
                int index = yout*widthTmp+xout;
                int pixelIV_ = (int)(rawpixels[index]&0xffff);
                double pixelV = (double)pixelIV_;
                //String msg2 = "OK 2! x = " + Integer.toString(xout) + ", y = " + Integer.toString(yout);
                //JOptionPane.showMessageDialog(null, msg2);
                xWeightedSum = xWeightedSum + (double)xout * pixelV;
                xSum = xSum + pixelV;
                yWeightedSum = yWeightedSum + (double)yout * pixelV;
                ySum = ySum + pixelV;
                }
            }
        double xWeightedCenter = xWeightedSum/xSum;
        double yWeightedCenter = yWeightedSum/ySum;
        /*
        String msg3 = "OK 3! xWeighted = " + Double.toString(xWeightedSum) + ", xSum = " + Double.toString(xSum) 
                + "yWeighted = " + Double.toString(yWeightedSum) + ", ySum = " + Double.toString(ySum) 
                + ", XC = " + Double.toString(xWeightedCenter) + ", YC = " + Double.toString(yWeightedCenter);
        JOptionPane.showMessageDialog(null, msg3);
        */
        
        double xWeightedCenterOri = (double)widthTmp/2;
        double yWeightedCenterOri = (double)heightTmp/2;
        
        double[] offset = new double[2];
        offset[0] = xWeightedCenter-xWeightedCenterOri;
        offset[1] = yWeightedCenter-yWeightedCenterOri;
        return offset;
        //ImageProcessor proc = studio_.data().ij().createProcessor(image);
        //proc.flipHorizontal();      //flip and rotate according current Yosemite eSPIM setup
        //proc = proc.rotateLeft();
        //imageFlip = studio_.data().ij().createImage(proc, image.getCoords(), image.getMetadata());
    }
}
 