package com.offline.clearvideo;
import org.junit.Test;
import static org.junit.Assert.*;
public class OutputProfileTest {
  @Test public void maintainsOrientation(){assertArrayEquals(new int[]{1080,1920},OutputProfile.size(720,1280,OutputProfile.HD));}
  @Test public void supportsQualityAiDouble(){assertArrayEquals(new int[]{640,360},OutputProfile.size(320,180,OutputProfile.QUALITY_AI));}
  @Test(expected=IllegalArgumentException.class) public void qualityAiInputLimit(){OutputProfile.size(1920,1080,OutputProfile.QUALITY_AI);}
  @Test public void supportsAiDouble(){assertArrayEquals(new int[]{1280,720},OutputProfile.size(640,360,OutputProfile.AI));}
  @Test public void normalSize(){assertArrayEquals(new int[]{1920,1080},OutputProfile.size(1920,1080,OutputProfile.NATIVE));}
  @Test(expected=IllegalArgumentException.class) public void aiLimit(){OutputProfile.size(1920,1080,OutputProfile.AI);}
  @Test(expected=IllegalArgumentException.class) public void fourKInputLimit(){OutputProfile.size(4096,2160,OutputProfile.NATIVE);}
  @Test public void bitrateBounds(){assertTrue(OutputProfile.bitrate(640,480,24)>0);assertTrue(OutputProfile.bitrate(3840,2160,60)<=30000000);}
}
