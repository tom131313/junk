// This project and file are derived in part from the "Pose Calib" project by
// @author Pavel Rojtberg
// It is subject to his license terms in the PoseCalibLICENSE file.

package Guidance;

import java.util.logging.Logger;

import org.opencv.core.Mat;
import org.opencv.core.Size;

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     keyframe class                                              */
/*                                     keyframe class                                              */
/*                                     keyframe class                                              */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
public class keyframe
{
	private static Logger LOGGER;
	static {
	  LOGGER = Logger.getLogger("");
	  LOGGER.finer("Loading");     
	}

  private Size img_size; // frame resolution WxH
  private Mat p3d; // object; the target ChArUcoBoard corners in 3d space but ours is always flat so Z = 0
  private Mat p2d; // image, the detected ccorners in 2d camera sensor
  private Mat pid; // detected ccorners ids

  // getters
  public Size img_size()
  {
    return img_size;
  }
  public Mat p3d()
  {
    return p3d;
  }
  public Mat p2d()
  {
    return p2d;
  }
  public Mat pid()
  {
    return pid;
  }

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     keyframe constructor                                        */
/*                                     keyframe constructor                                        */
/*                                     keyframe constructor                                        */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
public keyframe(Size img_size, Mat p3d, Mat p2d, Mat pid)
    {
      this.img_size = img_size;
      this.p3d = p3d;
      this.p2d = p2d;
      this.pid = pid;
      if (this.p2d.rows() != this.p3d.rows() || this.p2d.cols() != p3d.cols()
          || this.p2d.rows() != this.pid.rows())
      {
          LOGGER.severe("size of p2d != p3d\n" + this.p2d.dump() + "\n" + this.p3d.dump() + "\n" + this.pid.dump());
      }
    }
}
