package org.photonvision.calibrator;

import org.opencv.core.TermCriteria;

import edu.wpi.first.cscore.VideoMode.PixelFormat;

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     Cfg class                                                   */
/*                                     Cfg class                                                   */
/*                                     Cfg class                                                   */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
public class Cfg
{
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     Cfg constructor                                             */
/*                                     Cfg constructor                                             */
/*                                     Cfg constructor                                             */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    static final boolean isPV = true; // true assumes the PhotonVision environment available

    static final double var_terminate = 0.1;

    // Checks for the specified camera - laptop internal or USB external and uses it if present.
    // 0 internal if no external or if external connected after boot-up
    // 0 external if connected at boot-up
    // 1 internal if external connected at boot-up
    // 1 is external if connected after boot-up
    static int camId = 0;
    static PixelFormat pixelFormat = PixelFormat.kYUYV; // kMJPEG
    
    // a few icky int-float-double conversion scattered throughout the program.
    // camera image size and thus user display screen size
    static int image_width = 1280; // 640
    static int image_height = 720; // 480

    // ChArUco Board pixels = (board_x*square_len, board_y*square_len)
    static final int board_x = 9;
    static final int board_y = 6;
    static final int square_len = 280;
    static final int marker_len = 182;
    static final int dictionary = 0;
    static final boolean writeBoard = true;
    // intensity of the green guidance board
    // suggest "white" [-100]; (dull) -128 to -1 (bright)
    // suggest "black" [1]; (somewhat transparent) 1 to 64 (more obscuring)
    static final byte guidanceWhite = -100; // green actually; (black) 0 to 127 (medium), (medium) -128 to -1 (bright)
    static final byte guidanceBlack = 1; // ; (dark) 1 to 127, -128 to -1 (bright); must be much less than guidanceWhite and NOT 0
    // static final int guidanceTiffDPIx = 250;
    // static final int guidanceTiffDPIy = 250;
    static int resXDPM = 9843; // printing pixels per meter 9843 = 250 DPI
    static int resYDPM = 9843; // printing pixels per meter 9843 = 250 DPI
    
    // user config for convergence criteria
    static final int pt_min_markers = 1;
    static final double matchStillCidsMin = 0.8; // exclusive, Jaccard similarity of current and previous corner ids lists for mean flow calc 
    static final double mean_flow_max = 3.; // exclusive, larger is more movement allowed
    static final double pose_close_to_tgt_min = 0.8; // exclusive. was 0.85 overlap - the Jaccard score between shadow and actual img
    static final double MAX_OVERLAP = 0.9; // maximum fraction of distortion mask overlapping with this pose before pose considered not contributing enough to help fill distortion mask
    static final double minCorners = 6; // min for solvePnP (original needed 4 or 5 w/o solvePnP) but another place requires many more

    static final double DBL_EPSILON = Math.ulp(1.);
    static final TermCriteria calibrateCameraCriteria = new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, DBL_EPSILON);

    static final float FLT_EPSILON = Math.ulp(1.f);
    static final TermCriteria solvePnPRefineVVSCriteria = new TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 20, FLT_EPSILON);
    static final double solvePnPRefineVVSLambda = 1.;

    static final int wait = 1; // milliseconds to wait for user keyboard response to a new image
    static final int garbageCollectionFrames = 500; // camera frames - periodically do garbage collection because Java doesn't know there are big Mats to be released
    private Cfg()
    {
        throw new UnsupportedOperationException("This is a utility class");
    }
}
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     End Cfg class                                               */
/*                                     End Cfg class                                               */
/*                                     End Cfg class                                               */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
