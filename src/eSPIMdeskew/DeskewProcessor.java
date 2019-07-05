/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package eSPIMdeskew;

import jtransforms.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Exception;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Arrays;
import java.util.ArrayList;
import mmcorej.CMMCore;
import javax.swing.JOptionPane;

import ij.process.ImageProcessor;

import org.micromanager.Studio;
import org.micromanager.data.Image;
import org.micromanager.data.Processor;
import org.micromanager.data.ProcessorContext;
import org.micromanager.acquisition.SequenceSettings;
import org.micromanager.data.internal.multipagetiff.StorageMultipageTiff;
import org.micromanager.data.Datastore;
import org.micromanager.data.Coords;
import org.micromanager.data.Metadata;
import org.micromanager.display.DisplayWindow;
import org.micromanager.PositionList;
import org.micromanager.MultiStagePosition;
import org.micromanager.StagePosition;


import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;


/**
 *
 * @author Yina
 */
public class DeskewProcessor extends Processor{
    Studio studio_;
    CMMCore mmc;
    Datastore deskew;   //for deskewed data storage and display
    Datastore deskewMIP;   //for deskewed data maximum intensity projection storage and display
    Datastore brightFieldCorrection;
    
    DeskewConfigurator configurator_;   //Deskew processing settings
    SequenceSettings seqSettings_;      //get MDA acquisitiosn settings
    
    volatile int deskewVolumeid = 0;    //counting the number of deskewed volumes
    
    public int imageWidth,imageHight, newDeskewSize;
    public int framePerVolume;
    public int nchannel;
    public int nFrame;
    public int nposition = 1;
    public double deskewfactor;
    
    public boolean saveDeskew;
    public boolean saveLogFile;
    public String savePath;
    public String savePathMIP;
    public String savePathBrightFieldCorrection;
    
    public DisplayWindow displayDeskew;
    public DisplayWindow displayDeskewMIP;
    public DisplayWindow displayBrightFieldCorrection;
    
    public static int interval_;        //can be changed during acquisition
    
    short[] bufferMIP;
    short[] brightFieldImg;
    // log file    
    public String saveLogPath;
    public String saveLogPathDebug;
    
    public PrintWriter logFileWriter;
    // for debug
    public PrintWriter logFileWriterDebug;
    public PrintWriter dataFileWriter;
    
    public boolean wideFieldMotionCorrected;
    public int positionGroupNum;
    
    public boolean acqusitionValid;
    
    //brightfield correction
    double[] offset;
    int pixelsize;
    String xyStage;
    String zStage;
    
    double wideFieldPixelSize;
    
    
    public DeskewProcessor(Studio studio, DeskewConfigurator MyConfigurator)
    {
        acqusitionValid = true;
        
        studio_=studio;
        mmc=studio_.getCMMCore();
        configurator_ = MyConfigurator;
        seqSettings_ = studio_.acquisitions().getAcquisitionSettings();
        interval_ = configurator_.getVolumeinterval();
        
        double zstep = configurator_.getZstep();
        double angle = configurator_.getAngle();
        pixelsize = configurator_.getPixelsize();
        saveDeskew = configurator_.getSaveFileCheckbox();
        saveLogFile = configurator_.getSaveLogFileCheckbox();
        
        wideFieldMotionCorrected = configurator_.getWideFieldCorrectionCheckBox();
        positionGroupNum = configurator_.getPositionGroupNum();
        
        
        imageHight = (int) mmc.getImageWidth(); //swap height and width due to 90degree rotation
        imageWidth = (int) mmc.getImageHeight();
        framePerVolume = (int) seqSettings_.slices.size();
        nchannel = (int) seqSettings_.channels.size();
        nFrame = (int) seqSettings_.numFrames;
        if(seqSettings_.usePositionList){
            nposition = (int) studio_.positions().getPositionList().getNumberOfPositions();
        }else
        {
            nposition = 1;
        }
        
       
        
        // calculate parameters for deskew
        deskewfactor = (float)  Math.cos(angle * Math.PI / 180) * zstep / (pixelsize / 1000.0);
        newDeskewSize = (int) Math.ceil(imageWidth + deskewfactor * framePerVolume); 
        
        bufferMIP = new short[imageHight * newDeskewSize];
        brightFieldImg = new short[imageHight * imageWidth];
        Arrays.fill(bufferMIP, (short)0);
        Arrays.fill(brightFieldImg, (short)0);
        
        offset = new double[2];
        Arrays.fill(offset, (short)0);
        
        //xyStage = "XY";
        //zStage = "Z";
        xyStage = "XYStage";
        zStage = configurator_.getzStageName();
        //zStage = "TIZDrive";
        
        wideFieldPixelSize = 0.2167;
    } 
    
    @Override
    public void processImage(Image image, ProcessorContext pc) {
        //TODO: current workflow only works properly when acquisition order is
        //      time last. FIX.
        //TODO: add color display in deskew
        //initialize deskew multipage TIFF datastore and display at the beginning
        if (!acqusitionValid){
            return;
        }
        
        if(atAcquisitionBeginning(image)){
            if(saveDeskew){
                savePath = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_deskew"; 
                savePathMIP = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_deskew_MIP";
                // initialize log file writer
                if (saveLogFile){
                    saveLogPath = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_logFile.txt";
                    saveLogPathDebug = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_logFileDebug.txt";  
                    try {
                        logFileWriter = new PrintWriter(saveLogPath, "UTF-8");
                    } catch (IOException ex){
                        Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    try {
                        logFileWriterDebug = new PrintWriter(saveLogPathDebug, "UTF-8");
                    } catch (IOException ex){
                        Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }

                try {
                    deskew = studio_.data().createMultipageTIFFDatastore(savePath, true, StorageMultipageTiff.getShouldSplitPositions());
                    deskewMIP = studio_.data().createMultipageTIFFDatastore(savePathMIP, true, StorageMultipageTiff.getShouldSplitPositions());
                } catch (IOException ex) {
                    Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                }
            }else{
                deskew = studio_.data().createRAMDatastore();
                deskewMIP = studio_.data().createRAMDatastore();
            }
            
            // initialize every acqusition settings
            wideFieldMotionCorrected = configurator_.getWideFieldCorrectionCheckBox();
            positionGroupNum = configurator_.getPositionGroupNum();
            

            if(seqSettings_.usePositionList){
                nposition = (int) studio_.positions().getPositionList().getNumberOfPositions();
                if (nposition==0){
                    String errorMessage = "Position number in the list shoule be greater than 1!";
                    acqusitionValid = false;
                    JOptionPane.showMessageDialog(null, errorMessage);
                    return;
                }
            }else{
                nposition = 1;
            }
            framePerVolume = (int) seqSettings_.slices.size();
            nchannel = (int) seqSettings_.channels.size();
            nFrame = (int) seqSettings_.numFrames;
            zStage = configurator_.getzStageName();
            
            if (wideFieldMotionCorrected)
            {
                if (seqSettings_.channels.get(nchannel-1).doZStack==true)
                {
                    String errorMessage = "We are in Motion Corrected Mode, please abort the current acqusition and add brightfield channel"
                            + " without Z-Stack in the end";
                    acqusitionValid = false;
                    JOptionPane.showMessageDialog(null, errorMessage);
                    return;
                }
                if(!seqSettings_.usePositionList){
                    String errorMessage = "We are in Motion Corrected Mode, please use position list!";
                    acqusitionValid = false;
                    JOptionPane.showMessageDialog(null, errorMessage);
                    return;
                }
                if(nposition%4!=0 || nposition == 0){
                    String errorMessage = "We are in Motion Corrected Mode, the number of position must be the times of 4!";
                    acqusitionValid = false;
                    JOptionPane.showMessageDialog(null, errorMessage);
                    return;
                }
                positionGroupNum = nposition/4;
                
                brightFieldImg = new short[imageHight * imageWidth * positionGroupNum];
                Arrays.fill(brightFieldImg, (short)0);
                
                offset = new double[positionGroupNum*2];
                Arrays.fill(offset, (short)0);
                /*savePathBrightFieldCorrection = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "_brightField";
                try {
                    brightFieldCorrection = studio_.data().createMultipageTIFFDatastore(savePathBrightFieldCorrection, true, StorageMultipageTiff.getShouldSplitPositions());
                } catch (IOException ex) {
                    Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                }*/
                
            }
            else{
                if (seqSettings_.channels.get(nchannel-1).doZStack==false)
                {
                    String errorMessage = "We are not in Motion Corrected Mode, please abort the current acqusition and do Z-stack in all channels";
                    acqusitionValid = false;
                    JOptionPane.showMessageDialog(null, errorMessage);
                    return;
                }
            }
            
            
            if (saveLogFile){
                String acqusitonInfo = "Acqusition info: t=" + Integer.toString(nFrame)
                        + ", z=" + Integer.toString(framePerVolume)
                        + ", c=" + Integer.toString(nchannel)
                        + ", p=" + Integer.toString(nposition);
                logFileWriter.println(acqusitonInfo);
                //logFileWriterDebug.println(acqusitonInfo);
                
                acqusitonInfo = "Use positionList: " + Boolean.toString(seqSettings_.usePositionList);
                logFileWriter.println(acqusitonInfo);
                logFileWriter.println("");
                logFileWriter.println("");
                //logFileWriterDebug.println(acqusitonInfo);
                //logFileWriterDebug.close();
            }
            //

            displayDeskew = studio_.displays().createDisplay(deskew);
            studio_.displays().manage(deskew);
            displayDeskewMIP = studio_.displays().createDisplay(deskewMIP);
            studio_.displays().manage(deskewMIP);
            
            /*if(wideFieldMotionCorrected){
                displayBrightFieldCorrection = studio_.displays().createDisplay(brightFieldCorrection);
                studio_.displays().manage(brightFieldCorrection);
            }*/
            
        }
        
        
        int zIndex = image.getCoords().getZ();
        if (atTimepointBeginning(image)){
            //check interval_ updating at the beginning of each volume
            interval_ = configurator_.getVolumeinterval();
        }
        
        // deskew
        if(deskewRequested(image)){
            // for debugging
            /*
            if (saveLogFile){
                String imgInfo = getImageInfo(image);
                logFileWriter.println(imgInfo);
            }*/
            
            //add new images to deskew datastore
            Image deskewed = deskewSingleImage(image, framePerVolume, newDeskewSize, (float) deskewfactor);
            try {
                deskew.putImage(deskewed);
                if(zIndex == (framePerVolume - 1)){
                    deskewMIP.putImage(creatMIPImage(bufferMIP, image));
                    Arrays.fill(bufferMIP, (short) 0);
                }
            } catch (IOException ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            
            if (atTimepointEnd(image)){
                deskewVolumeid++; // avoid wrong index when user change interval value during acquisition
            }
        } 
        
        // write log file
        if (updateInfoRequest(image)){
            //String logMessage = "laoziyaoshuchule";
            //JOptionPane.showMessageDialog(null, logMessage);
            // imageInfo
            updateInfo(image);
            
        }
        
        
        if(atAcquisitionEnd(image) && saveDeskew){
            try {
                //String logMessage = "laozitingle";
                //JOptionPane.showMessageDialog(null, logMessage);
                deskew.freeze();
                deskew.save(Datastore.SaveMode.MULTIPAGE_TIFF, savePath);
                deskew.close();

                deskewMIP.freeze();
                deskewMIP.save(Datastore.SaveMode.MULTIPAGE_TIFF, savePathMIP);
                deskewMIP.close();

            } catch (IOException ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            /*if(wideFieldMotionCorrected){
                try {
                    brightFieldCorrection.freeze();
                    brightFieldCorrection.save(Datastore.SaveMode.MULTIPAGE_TIFF, savePathBrightFieldCorrection);
                    brightFieldCorrection.close();
                } catch (IOException ex) {
                    Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
                }
            }*/
            if (saveLogFile){
                //String logMessage = "laoziguanle";
                //JOptionPane.showMessageDialog(null, logMessage);
                //logFileWriter.println(logMessage);
                logFileWriter.close();
                logFileWriterDebug.close();
            }
        }
        
        // motion correction
        if(wideFieldMotionCorrected){
            if (firstBrightFieldFrame(image)){
                initialBrightFieldBuffer(image);
            }
            if (regularBrightFieldFrame(image)){
                getOffsetAndUpdateBuffer(image);
                // update the group of positionList
            }
            if (upDatePostionListFrame(image)){
                upDatePositionList(image);
            }
        }
        pc.outputImage(image);
        /*int channelIndex = image.getCoords().getChannel();
        if (channelIndex==2){
            int xxx = 0;
            xxx = xxx + 1;
        }
        if(wideFieldMotionCorrected&&(channelIndex==nchannel-1)){
            try {
                brightFieldCorrection.putImage(creatBrightFieldImage(image));
            }catch (IOException ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }  
        }else{
           pc.outputImage(image);  
        }*/
    }
    
    private boolean updateInfoRequest(Image image){
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();
        if (!wideFieldMotionCorrected){
            return ( saveLogFile && zIndex == (framePerVolume-1) && channelIndex == (nchannel - 1));
        }else{
            return ( saveLogFile && zIndex == (framePerVolume-1) && channelIndex == (nchannel - 2));
        }
        
    }
    
    // only use for debugging
    private String getImageInfo(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();  
        int positionIndex = image.getCoords().getStagePosition();
        
        String info = "Image info: t=" + Integer.toString(timeIndex) + ", z=" + Integer.toString(zIndex)
                + ", c=" + Integer.toString(channelIndex) + ", pos=" + Integer.toString(positionIndex);
        return info;
    }
    
    private boolean deskewRequested(Image image){
        int index = image.getCoords().getTimePoint();
        int channelIndex = image.getCoords().getChannel();  
        if (!wideFieldMotionCorrected){
            return (index % interval_ == 0);
        }else{
            return ((index % interval_ == 0)&&(channelIndex!=(nchannel - 1)));
        }
    }
    
    private boolean atTimepointBeginning(Image image){
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();  
        int positionIndex = image.getCoords().getStagePosition();
        
        return (zIndex == 0 && channelIndex == 0 && positionIndex == 0);
    }
    
    private boolean atTimepointEnd(Image image){
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();  
        int positionIndex = image.getCoords().getStagePosition();
        if (!wideFieldMotionCorrected){
            return (zIndex == (framePerVolume-1) && channelIndex == (nchannel - 1) && positionIndex == (nposition - 1));
        }else{
            return (zIndex == (framePerVolume-1) && channelIndex == (nchannel - 2) && positionIndex == (nposition - 1));
        }
    }
    
    private boolean atAcquisitionBeginning(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();  
        int positionIndex = image.getCoords().getStagePosition();
        
        return (timeIndex == 0 && zIndex == 0 && channelIndex == 0 && positionIndex == 0);
    }
    
    private boolean atAcquisitionEnd(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int zIndex = image.getCoords().getZ();
        int channelIndex = image.getCoords().getChannel();
        int positionIndex = image.getCoords().getStagePosition();
        
        int timeEnd = (int) seqSettings_.numFrames;
        if (!wideFieldMotionCorrected){
            return (timeIndex == (timeEnd-1) && zIndex == (framePerVolume-1) && channelIndex == (nchannel - 1) && positionIndex == (nposition - 1));
        }else{
            return (timeIndex == (timeEnd-1) && zIndex == (framePerVolume-1) && channelIndex == (nchannel - 2) && positionIndex == (nposition - 1));
        }
    }
    
    private Image deskewSingleImage(Image image, int framePerVolume, int newDeskewSize, float deskewFactor){
        Image newImage, imageFlip;
        
        //TODO: flip and rotate image using user specific parameters.
        ImageProcessor proc = studio_.data().ij().createProcessor(image);
        proc.flipHorizontal();      //flip and rotate according current Yosemite eSPIM setup
        proc = proc.rotateLeft();
        imageFlip = studio_.data().ij().createImage(proc, image.getCoords(), image.getMetadata());
        
        short[] deskewpixels = new short[imageHight * newDeskewSize];
        short rawpixels[] =(short[]) imageFlip.getRawPixels();//reference to rawpixels of image
        
        Coords.CoordsBuilder coordsBuilder = image.getCoords().copy();               //get coords builder
        Metadata.MetadataBuilder metadataBuilder = image.getMetadata().copy();        //get metadata builder
        coordsBuilder.time(deskewVolumeid);
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
                    //use rawpixels in unsigned way
                    short weighted = (short)((1-offset)*(int)(rawpixels[index]&0xffff) + offset*(int)(rawpixels[index+1]&0xffff));
                    deskewpixels[yout*newDeskewSize+xout] = weighted;
                }else{
                    deskewpixels[yout*newDeskewSize+xout]=zeropad;
                }
            }
        }
        // seems that there is a trigger problem with the first z in each cycle
        if(zout != 0){
            maxProject(bufferMIP, deskewpixels);
        }
        
        newImage = studio_.data().createImage(deskewpixels, newDeskewSize, imageHight, 2, 1, coords, metadata);
        return newImage;
    }
    
    private void maxProject(short[] buffer, short[] newpixels){
        for (int i = 0; i < imageHight * newDeskewSize; i++){
            buffer[i] = (buffer[i] > newpixels[i])? buffer[i]:newpixels[i];
        }
    }
    
    private Image creatBrightFieldImage(Image image){
        Image bfImage;
        int width = image.getWidth();
        int height = image.getHeight();
        short pixels[] =(short[]) image.getRawPixels();
        Coords.CoordsBuilder coordsBuilder = image.getCoords().copy();
        Metadata.MetadataBuilder metadataBuilder = image.getMetadata().copy(); 
        coordsBuilder.z(0);
        Coords coords = coordsBuilder.build();
        Metadata metadata = metadataBuilder.build();
        bfImage = studio_.data().createImage(pixels, width, height, 2, 1, coords, metadata);
        return bfImage;
    }
    
    private Image creatMIPImage(short[] buffer, Image image){
        Image mipImage;
        Coords.CoordsBuilder coordsBuilder = image.getCoords().copy();               //get coords builder
        Metadata.MetadataBuilder metadataBuilder = image.getMetadata().copy();        //get metadata builder
        coordsBuilder.time(deskewVolumeid);
        coordsBuilder.z(0);  
        Coords coords = coordsBuilder.build();
        Metadata metadata = metadataBuilder.build();
        
        mipImage = studio_.data().createImage(buffer, newDeskewSize, imageHight, 2, 1, coords, metadata);
        return mipImage;
    }
    
    // for brightFieldCorrection
    // use positionind = 1 to calibration
    private boolean firstBrightFieldFrame(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int channelIndex = image.getCoords().getChannel();
        int positionIndex = image.getCoords().getStagePosition();
        return ( (timeIndex == 0) && (channelIndex==(nchannel - 1)) && ((positionIndex+2)%4==0) );
    }
    
    private boolean regularBrightFieldFrame(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int channelIndex = image.getCoords().getChannel();
        int positionIndex = image.getCoords().getStagePosition();
        return ( (timeIndex != 0) && (channelIndex==(nchannel - 1)) && ((positionIndex+3)%4==0) );
    }
    
    private boolean upDatePostionListFrame(Image image){
        int timeIndex = image.getCoords().getTimePoint();
        int channelIndex = image.getCoords().getChannel();
        int positionIndex = image.getCoords().getStagePosition();
        int zIndex = image.getCoords().getZ();
        return ( (timeIndex != 0) && (channelIndex==(nchannel - 1)) && ((positionIndex+1)%4==0) );
    }
    
    private void upDatePositionList(Image image){
        int positionIndex = image.getCoords().getStagePosition();
        int groupIndex = positionIndex/(int)4;
        
        PositionList pl = studio_.positions().getPositionList();
        //PositionList pl_new = new PositionList();
        //int posNum = pl.getNumberOfPositions();
        //double[] currentOffset = new double[2];
        double currentOffset0 = offset[2*groupIndex];
        double currentOffset1 = offset[2*groupIndex+1];
        double tsh = 5;
        double kFactor = 0.5;
        
        if ((java.lang.Math.abs(currentOffset0)<tsh)&&(java.lang.Math.abs(currentOffset1)<tsh)){
            for(int posInd = 0;posInd<4;posInd++){
                MultiStagePosition msp = pl.getPosition(posInd+4*groupIndex);
                String lb = msp.getLabel();
                double xx = msp.get(xyStage).x;
                double yy = msp.get(xyStage).y;
                double zz = msp.get(zStage).x;



                // crucial
                //MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx+0.9*offsetForUpdate[0]*pixelSize, yy-0.9*offsetForUpdate[1]*pixelSize, zStage, zz);
                //MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx-0.9*offsetForUpdate[1]*pixelSize, yy+0.9*offsetForUpdate[0]*pixelSize, zStage, zz);
                MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx+currentOffset0*kFactor, yy-currentOffset1*kFactor, zStage, zz);
                //MultiStagePosition msp_new = new MultiStagePosition(xyStage, xx, yy, zStage, zz);
                msp_new.setLabel(lb);
                pl.replacePosition(posInd+4*groupIndex, msp_new);
            }
            studio_.positions().setPositionList(pl);    
        }
        
        
    }
    
    private void initialBrightFieldBuffer(Image image){
        int positionIndex = image.getCoords().getStagePosition();
        int groupIndex = positionIndex/(int)4;
        int basedInd = groupIndex * imageHight * imageWidth;
        short rawpixels[] = (short[]) image.getRawPixels();
        
        for (int i = 0; i < imageHight * imageWidth; i++){
            brightFieldImg[basedInd+i] = rawpixels[i];
        }
        
    }
    
    
    private void getOffsetAndUpdateBuffer(Image image){
        double[] currentOffset = new double[2];
        currentOffset[0] = 0;
        currentOffset[1] = 0;
        
        int positionIndex = image.getCoords().getStagePosition();
        int groupIndex = positionIndex/(int)4;
        int basedInd = groupIndex * imageHight * imageWidth;
        short newpixels[] = (short[]) image.getRawPixels();
        short[] oldpixels = new short[imageHight * imageWidth];
        
        for (int i = 0; i < imageHight * imageWidth; i++){
            oldpixels[i] = brightFieldImg[basedInd+i] ;
        }
        
        
        //short oldpixels[] = Arrays.copyOfRange(brightFieldImg, basedInd, basedInd + imageHight * imageWidth );
        // only for debugging
        // new
        int timeIndex = image.getCoords().getTimePoint();
        /*String saveDataFile;
        saveDataFile = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "dataNew_"+ Integer.toString(timeIndex)+".txt"; 
        try {
             dataFileWriter = new PrintWriter(saveDataFile, "UTF-8");
        } catch (IOException ex){
            Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        for(int i = 0; i < imageHight * imageWidth; i++){
            dataFileWriter.println((int)newpixels[i]);
        }
        dataFileWriter.close();
        // old
        saveDataFile = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "dataOld_"+ Integer.toString(timeIndex)+".txt"; 
        try {
             dataFileWriter = new PrintWriter(saveDataFile, "UTF-8");
        } catch (IOException ex){
            Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        for(int i = 0; i < imageHight * imageWidth; i++){
            dataFileWriter.println((int)oldpixels[i]);
        }
        dataFileWriter.close();
        */
        
        // fft here
        //FloatFFT_2D fft = new FloatFFT_2D((long)imageHight,(long)imageWidth);
        FloatFFT_2D fft = new FloatFFT_2D((long)imageWidth,(long)imageHight);
        //float[] dataOldOri = new float[2 * imageHight* imageWidth];
        //float[] dataNewOri = new float[2 * imageHight* imageWidth];
        
        float[] dataOld = new float[2 * imageHight* imageWidth];
        float[] dataNew = new float[2 * imageHight* imageWidth];
        
        for (int i = 0; i < imageHight * imageWidth; i++){
            dataOld[2*i] = (float)oldpixels[i];
            dataOld[2*i+1] = (float)0;
            //dataOldOri[2*i] = (float)oldpixels[i];
            //dataOldOri[2*i+1] = (float)0;
        } 
                
        for (int i = 0; i < imageHight * imageWidth; i++){
            dataNew[2*i] = (float)newpixels[i];
            dataNew[2*i+1] = (float)0;
            //dataNewOri[2*i] = (float)newpixels[i];
            //dataNewOri[2*i+1] = (float)0;
        } 
        
        fft.complexForward(dataNew);
        fft.complexForward(dataOld);
        float[] corr = complexMuliplyArray(dataNew,dataOld,imageHight,imageWidth);
        fft.complexInverse(corr, false);
        //double[] corrAbs = getAbs(corr,imageHight,imageWidth);
        // find maximum
        //currentOffset = getMaxAbs(corr,imageHight, imageWidth);
        currentOffset = getMaxAbsNew(corr,imageHight, imageWidth);
        if (saveLogFile){
            String offsetInfo = "Offset: group" + Integer.toString(groupIndex) + ", offset0 = " + Double.toString(currentOffset[0])
                    + ", offset1 = " + Double.toString(currentOffset[1]);
            
            logFileWriter.println(offsetInfo);
            logFileWriter.println("");
            logFileWriter.println("");
        }
        offset[2*groupIndex] = currentOffset[0];
        offset[2*groupIndex+1] = currentOffset[1];
        
        // update buffer
        for (int i = 0; i < imageHight * imageWidth; i++){
            brightFieldImg[basedInd+i] = newpixels[i];
        }
        // only for debug
        // corr
        /*saveDataFile = studio_.displays().getCurrentWindow().getDatastore().getSavePath() + "corr_"+ Integer.toString(timeIndex)+".txt"; 
        try {
             dataFileWriter = new PrintWriter(saveDataFile, "UTF-8");
        } catch (IOException ex){
            Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        for(int i = 0; i < imageHight * imageWidth; i++){
            dataFileWriter.println(corr[2*i]);
            dataFileWriter.println(corr[2*i+1]);
        }        
        dataFileWriter.close();
        */
    }
    private float[] complexMuliplyArray(float[] arrayA, float[] arrayB, int arrayHight, int arrayWidth){
        float[] result = new float[2*arrayHight* arrayWidth];
        for (int i = 0; i < arrayHight* arrayWidth; i++){
            float realA =  arrayA[2*i];
            float imagA =  arrayA[2*i+1];
            float realB =  arrayB[2*i];
            float imagB =  arrayB[2*i+1];
            float realR = realA*realB + imagA*imagB;
            float imagR = imagA*realB - realA*imagB;
            result[2*i] = realR;
            result[2*i+1] = imagR;
        }
        
        return result;
    }
    private double[] getAbs(float[] arrayA,int arrayHight, int arrayWidth){
        double[] result = new double[arrayHight* arrayWidth];
        for (int i = 0; i < arrayHight* arrayWidth; i++){
            double real = (double) arrayA[2*i];
            double imag = (double) arrayA[2*i+1];
            result[i] = java.lang.Math.sqrt(real*real+imag*imag);
        }
        return result;
    }
    private double[] getMaxAbs(float[] arrayA,int arrayHight, int arrayWidth){
        int[] maxInd = new int[2];
        maxInd[0] = 0;
        maxInd[1] = 0;
        double maxV = 0;
        for (int i = 0; i < arrayHight; i++){
            for (int j = 0; j < arrayWidth; j++){
                int ind = i*arrayWidth+j;
                double real = (double) arrayA[2*ind];
                double imag = (double) arrayA[2*ind+1];
                double abs = java.lang.Math.sqrt(real*real+imag*imag);
                if (abs>maxV){
                    maxInd[0] = i;
                    maxInd[1] = j;
                    maxV = abs;
                }
            }
        }
        int halfHight = arrayHight/2;
        int halfWidth = arrayWidth/2;
        if (maxInd[0]>halfHight){
           maxInd[0] =  maxInd[0] - arrayHight;
        }
        if (maxInd[1]>halfWidth){
           maxInd[1] =  maxInd[1] - arrayWidth;
        }
        
        double[] maxPos = new double[2];
        maxPos[0] = (double)maxInd[0]*(double) pixelsize / (double)1000;
        maxPos[1] = (double)maxInd[1]*(double) pixelsize / (double)1000;
        return maxPos;
    }
    private double[] getMaxAbsNew(float[] arrayA,int arrayHight, int arrayWidth){
        int[] maxInd = new int[2];
        maxInd[0] = 0;
        maxInd[1] = 0;
        double maxV = 0;
        for (int i = 0; i < arrayWidth; i++){
            for (int j = 0; j < arrayHight; j++){
                int ind = i*arrayHight+j;
                double real = (double) arrayA[2*ind];
                double imag = (double) arrayA[2*ind+1];
                double abs = java.lang.Math.sqrt(real*real+imag*imag);
                if (abs>maxV){
                    maxInd[0] = i;
                    maxInd[1] = j;
                    maxV = abs;
                }
            }
        }
        int halfHight = arrayHight/2;
        int halfWidth = arrayWidth/2;
        if (maxInd[0]>=halfWidth){
           maxInd[0] =  maxInd[0] - arrayWidth;
        }
        if (maxInd[1]>=halfHight){
           maxInd[1] =  maxInd[1] - arrayHight;
        }
        
        double[] maxPos = new double[2];
        maxPos[0] = (double)maxInd[0]*wideFieldPixelSize;
        maxPos[1] = (double)maxInd[1]*wideFieldPixelSize;
        return maxPos;
    }
    
    private void updateInfo(Image image){
        if (!wideFieldMotionCorrected){
            String logMessage = "";
            int currentTimeIndex = image.getCoords().getTimePoint();
            int currentPositionIndex = image.getCoords().getStagePosition();
            String imageInfo = "Image: t = " + Integer.toString(currentTimeIndex)
                    + ", pos = " + Integer.toString(currentPositionIndex);
            logMessage = imageInfo;
            logFileWriter.println(logMessage);

            // current position
            // for mm demo
            //String xyStage = "XY";
            //String zStage = "Z";

            // for eSPIM
            // target Postion

            PositionList pl = studio_.positions().getPositionList();
            MultiStagePosition msp = pl.getPosition(currentPositionIndex);
            if (msp!=null){
                String lb = msp.getLabel();
                double xx = msp.get(xyStage).x;
                double yy = msp.get(xyStage).y;
                double zz = msp.get(zStage).x;
                String posInfo = "Target positon: x = " + Double.toString(xx)
                        + ", y = " + Double.toString(yy)
                        + ", z = " + Double.toString(zz);
                logMessage = posInfo;
                logFileWriter.println(logMessage);
            }else{
                logMessage = "No target position!";
                logFileWriter.println(logMessage);
            }
            // interested property

            double realXpostion = 0;
            double realYpostion = 0;
            double realZpostion = 0;
            try{
                 realXpostion = mmc.getXPosition(xyStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            try{
                realYpostion = mmc.getYPosition(xyStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            try{
                realZpostion = mmc.getPosition(zStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            String realPosInfo = "Real positon: x = " + Double.toString(realXpostion)
                    + ", y = " + Double.toString(realYpostion)
                    + ", z = " + Double.toString(realZpostion);
            logMessage = realPosInfo;
            logFileWriter.println(logMessage);

            /*
            // Add interested device property to log file
            // for debug use mmc.getProperty("Camera", "Mode");

            String propertyInfo ="";
            String propertyDevice;
            String propertyName;

            // group by group
            propertyDevice = "TIPSFOffset";
            propertyName = "status";
            try{
                 propertyInfo = mmc.getProperty(propertyDevice, propertyName);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            logMessage  = propertyDevice + "-" + propertyName + " : " + propertyInfo;
            logFileWriter.println(logMessage);
            */

            logFileWriter.println("");
            logFileWriter.println("");
        }else{
            String logMessage = "";
            int currentTimeIndex = image.getCoords().getTimePoint();
            int currentPositionIndex = image.getCoords().getStagePosition();
            String imageInfo = "Image: t = " + Integer.toString(currentTimeIndex)
                    + ", pos = " + Integer.toString(currentPositionIndex);
            logMessage = imageInfo;
            logFileWriter.println(logMessage);

            // current position
            // for mm demo
            //String xyStage = "XY";
            //String zStage = "Z";

            // for eSPIM
            // target Postion

            PositionList pl = studio_.positions().getPositionList();
            MultiStagePosition msp = pl.getPosition(currentPositionIndex);
            if (msp!=null){
                String lb = msp.getLabel();
                double xx = msp.get(xyStage).x;
                double yy = msp.get(xyStage).y;
                double zz = msp.get(zStage).x;
                String posInfo = "Target positon: x = " + Double.toString(xx)
                        + ", y = " + Double.toString(yy)
                        + ", z = " + Double.toString(zz);
                logMessage = posInfo;
                logFileWriter.println(logMessage);
            }else{
                logMessage = "No target position!";
                logFileWriter.println(logMessage);
            }
            // interested property

            double realXpostion = 0;
            double realYpostion = 0;
            double realZpostion = 0;
            try{
                 realXpostion = mmc.getXPosition(xyStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            try{
                realYpostion = mmc.getYPosition(xyStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            try{
                realZpostion = mmc.getPosition(zStage);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            String realPosInfo = "Real positon: x = " + Double.toString(realXpostion)
                    + ", y = " + Double.toString(realYpostion)
                    + ", z = " + Double.toString(realZpostion);
            logMessage = realPosInfo;
            logFileWriter.println(logMessage);

            /*
            // Add interested device property to log file
            // for debug use mmc.getProperty("Camera", "Mode");

            String propertyInfo ="";
            String propertyDevice;
            String propertyName;

            // group by group
            propertyDevice = "TIPSFOffset";
            propertyName = "status";
            try{
                 propertyInfo = mmc.getProperty(propertyDevice, propertyName);
            }  catch (Exception ex) {
                Logger.getLogger(DeskewProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            logMessage  = propertyDevice + "-" + propertyName + " : " + propertyInfo;
            logFileWriter.println(logMessage);
            */

            logFileWriter.println("");
            logFileWriter.println("");
            
        }
    }
}
 