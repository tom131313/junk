package calibrator;

import static calibrator.ArrayUtils.argmax;
import static calibrator.ArrayUtils.argmin;
import static calibrator.ArrayUtils.isAllTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     UserGuidance class                                                     */
/*                                     UserGuidance class                                                     */
/*                                     UserGuidance class                                                     */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
public class UserGuidance {

    static {Main.LOGGER.log(Level.CONFIG, "Starting ----------------------------------------");}

    private Calibrator calib;

    private String[] AX_NAMES = {"red", "green", "blue"};
    private String[] INTRINSICS = {"fx", "fy", "cx", "cy", "k1", "k2", "p1", "p2", "k3"};
    private String[] POSE = {"fx", "ry", "rz", "tx", "ty", "tz"};

    // parameters that are optimized by the same board poses
    private int PARAM_GROUPS[][] = {{0, 1, 2, 3}, {4, 5, 6, 7, 8}}; // grouping and numbering the INTRINSICS

    // get geometry from tracker
    private ChArucoDetector tracker;
    private int allpts;
    private int square_len;
    private int marker_len;
    private int SQUARE_LEN_PIX = 12; // immediately changed in constructor. Why init here? Maybe 0 init would be safer.

    private Size img_size;
    private Mat overlap;
    private BoardPreview board;
    private Mat board_units;
    private Mat board_warped = new Mat();
    private double var_terminate;
    private boolean converged = false;
    private boolean[] pconverged;
    private double min_reperr_init = Double.POSITIVE_INFINITY;

    private int tgt_param = -1; // not sure this needs init but it came from None in Python which throws error if accessed

    // actual user guidance
    private boolean pose_reached = false;
    private boolean capture = false;
    private boolean still = false;
    private String user_info_text = "";

    private PoseGeneratorDist posegen;
    private Mat tgt_r = new Mat();
    private Mat tgt_t = new Mat();

    // getters
    boolean converged(){return converged;}
    String user_info_text(){return user_info_text;}
    Mat tgt_r(){return tgt_r;}
    Mat tgt_t(){return tgt_t;}

/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     UserGuidance constructor                                               */
/*                                     UserGuidance constructor                                               */
/*                                     UserGuidance constructor                                               */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    UserGuidance(ChArucoDetector tracker, double var_terminate) throws Exception // force use of var_terminate=0.1 instead of defaulting
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        this.tracker = tracker;
        this.var_terminate = var_terminate;
        calib = new Calibrator(tracker.img_size);
        pconverged = new boolean[calib.nintr()];
        Arrays.fill(pconverged, false);

        this.allpts = (Cfg.board_x-1)*(Cfg.board_y-1); // board w = 9 h = 6 => 54 squares; 8x5 => 40 interior corners
        this.square_len = Cfg.square_len;
        this.marker_len = Cfg.marker_len;
        this.SQUARE_LEN_PIX = this.square_len;
        this.img_size = tracker.img_size;
        this.overlap = Mat.zeros((int)this.img_size.height, (int)this.img_size.width, CvType.CV_8UC1);

        // preview image
        this.board = new BoardPreview(this.tracker.boardImage);
        // desired pose of board for first frame
        // translation defined in terms of board dimensions
        board_units = new Mat(3, 1, CvType.CV_64FC1);
        board_units.put(0, 0, tracker.board_sz().width*square_len);
        board_units.put(1, 0, tracker.board_sz().height*square_len);
        board_units.put(2, 0, tracker.board_sz().width*square_len);
        this.posegen = new PoseGeneratorDist(img_size);

        // set first pose
        this.set_next_pose();
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     calibrate                                                              */
/*                                     calibrate                                                              */
/*                                     calibrate                                                              */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    private void calibrate() throws Exception
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        if(this.calib.keyframes.size() < 2) // need at least 2 keyframes
        {
            return;
        }

        double[] pvar_prev = this.calib.varIntrinsics().clone(); // calib still has the previous intrinsics
        boolean first = this.calib.keyframes.size() == 2;

        List<keyframe> placeholder = new ArrayList<>(20); // dummy so I don't have to overload the method
        // compute the new intrinsics
        double[] index_of_dispersion = this.calib.calibrate(placeholder).clone();  // local copy so original not changed

        double[] pvar = this.calib.varIntrinsics(); // save the new intrinsics - just a shorter name

        double[] rel_pstd = new double[pvar.length];
        if(! first)
        {      
            double total_var_prev = Arrays.stream(pvar_prev).sum();
            double total_var = Arrays.stream(pvar).sum();

            if(total_var > total_var_prev)
            {
                Main.LOGGER.log(Level.WARNING, "note: total var degraded");
            }
            // check for convergence
            for(int i = 0; i < pvar.length; i++)
            {
                rel_pstd[i] = 1 - Math.sqrt(pvar[i]) / Math.sqrt(pvar_prev[i]);
            }

            Main.LOGGER.log(Level.WARNING, "relative stddev " + Arrays.toString(rel_pstd));
            
            if(rel_pstd[this.tgt_param] < 0)
            {
                Main.LOGGER.log(Level.WARNING, this.INTRINSICS[this.tgt_param] + " degraded");
            }

            // g (0, 1, 2, 3)  p 0 p 1 p 2 p 3 g (4, 5, 6, 7, 8) p 4 p 5 p 6 p 7 p 8
            for(int gIdx=0; gIdx< this.PARAM_GROUPS.length; gIdx++) // loop through all groups (2 groups)
            {
                int[] g = this.PARAM_GROUPS[gIdx]; // change the name to match original

                // check if in this group
                boolean inGroup = false; // first assume not in this group
                for(int p : g) // loop through whole group (4 or 5 items)
                {
                    if(this.tgt_param == p)
                    {
                        inGroup = true; // found it in this group
                        break; // so no need to check further
                    }
                }
                if( ! inGroup)
                {
                    continue; // not in this group so move on to next group            
                }

                StringBuilder converged = new StringBuilder();

                for(int p : g)
                {
                    // if index_of_dispersion[p] < 0.05:
                    if(rel_pstd[p] > 0 && rel_pstd[p] < this.var_terminate)
                    {    
                        if(! this.pconverged[p])
                          {
                            converged.append(this.INTRINSICS[p]);
                            this.pconverged[p] = true;
                          }
                    }
                }
                if( ! converged.isEmpty())
                {
                    Main.LOGGER.log(Level.WARNING, "{" + converged + "} converged");
                }
            }
        }
        // if an intrinsic has converged, then set it to 0 so it can't be selected (again) as the max 
        for(int i = 0; i < this.pconverged.length; i++)
        {
            if(this.pconverged[i])
            {
                index_of_dispersion[i] = 0.;
            }
        }
        
        this.tgt_param = argmax(index_of_dispersion);
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     set_next_pose                                                          */
/*                                     set_next_pose                                                          */
/*                                     set_next_pose                                                          */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    private void set_next_pose() throws Exception
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        int nk = this.calib.keyframes.size();
    
        List<Mat> rt = this.posegen.get_pose(this.board_units, // rotation and translation of the guidance board
                                            nk,
                                            this.tgt_param,
                                            this.calib.K(),
                                            this.calib.cdist());
        rt.get(0).copyTo(this.tgt_r);
        rt.get(1).copyTo(this.tgt_t);

        Main.LOGGER.log(Level.WARNING, "rt1 " + tgt_r.dump() + " " + tgt_t.dump());
        rt.get(1).release();
        rt.remove(1);
        rt.get(0).release();
        rt.remove(0);
        
        this.board.create_maps(this.calib.K(), this.calib.cdist(), this.img_size);
        // make the guidance board warped and right size
        //board_warped_shape =  # Height Width Channels (720, 1280, 3)
        this.board_warped.release(); // rkt
        Main.LOGGER.log(Level.WARNING, "rt2 " + this.tgt_r.dump() + " " + this.tgt_t.dump());

        this.board_warped = this.board.project(this.tgt_r, this.tgt_t, false, Imgproc.INTER_NEAREST);

        Main.LOGGER.log(Level.WARNING, "board_warped created r/t " + this.tgt_r.dump() + this.tgt_t.dump()  + board_warped);
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     pose_close_to_tgt                                                      */
/*                                     pose_close_to_tgt                                                      */
/*                                     pose_close_to_tgt                                                      */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    private double pose_close_to_tgt()
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        Main.LOGGER.log(Level.WARNING, "pose_valid " + this.tracker.pose_valid() + ", tgt_r empty " + this.tgt_r.empty());
        
        double jaccard = 0.;
    
        if( ! this.tracker.pose_valid())
            return jaccard;

        if(this.tgt_r.empty())
            return jaccard;
    
        byte[] board_warpedArray = new byte[this.board_warped.rows()*this.board_warped.cols()*this.board_warped.channels()];
        this.board_warped.get(0, 0, board_warpedArray); // efficient retrieval of board_warped

        byte[] overlapArray = new byte[this.overlap.rows()*this.overlap.cols()]; // 1 channel, java sets this to all zeros

        int indexBoard_warpedArray = 1; // start position; extracting channel 1 (of 0, 1, 2)
        for(int row = 0; row < overlapArray.length; row++)
        {
            if(board_warpedArray[indexBoard_warpedArray] != 0)
            {
                overlapArray[row] = 1;
            }
            indexBoard_warpedArray += 3; // bump to next pixel; incrementing by number of channels
        }
        this.overlap.put(0, 0, overlapArray);

        this.overlap.copyTo(Main.testImg2); // test 2 has the guidance board b&w//FIXME at this point the rotation of the shadow board is reversed from Python
        Core.multiply(Main.testImg2, new Scalar(175.), Main.testImg2);
        Imgproc.putText(Main.testImg2, Main.frame, new Point(0, 20), Imgproc.FONT_HERSHEY_SIMPLEX, .8, new Scalar(0, 0, 0), 4);
        Imgproc.putText(Main.testImg2, Main.frame, new Point(0, 20), Imgproc.FONT_HERSHEY_SIMPLEX, .8, new Scalar(255, 255, 255), 2);

        int Aa = Core.countNonZero(this.overlap); // number of on (1) pixels in the warped_board (from above)

        Mat tmp = this.board.project(this.tracker.rvec(), // create projected shadow same way as the guidance board (hopefully)
                                    this.tracker.tvec(), 
                                    true,
                                    Imgproc.INTER_NEAREST);
                                    
        tmp.copyTo(Main.testImg1); // test 1 has the board projected from where the detector thinks is the camera image pose
        Core.multiply(Main.testImg1, new Scalar(255.), Main.testImg1);
        Imgproc.putText(Main.testImg1, Main.frame, new Point(0, 20), Imgproc.FONT_HERSHEY_SIMPLEX, .8, new Scalar(0, 0, 0), 4);
        Imgproc.putText(Main.testImg1, Main.frame, new Point(0, 20), Imgproc.FONT_HERSHEY_SIMPLEX, .8, new Scalar(255, 255, 255), 2);
        
        Main.LOGGER.log(Level.WARNING, "shadow_warped created r/t " + this.tracker.rvec().dump() + this.tracker.tvec().dump()  + board_warped);

        int Ab = Core.countNonZero(tmp); // number of on (1) pixels in the warped shadow board
        Core.bitwise_and(this.overlap, tmp, this.overlap); // make the overlapped pixels on (1)
        int Aab = Core.countNonZero(this.overlap); // number of on (1) pixels that overlap on the 2 boards
        
        // circumvents instability during initialization and large variance in depth later on
        // Jaccard similarity index
        jaccard = (double)Aab / (double)(Aa + Ab - Aab);

        tmp.release();
        Main.LOGGER.log(Level.WARNING, "jaccard " + jaccard);
    
        return jaccard;
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     update                                                                 */
/*                                     update                                                                 */
/*                                     update                                                                 */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    /**
     * @param force
     * @return true if a new pose was captured
     * @throws Exception
     */ 
    boolean update(boolean force) throws Exception
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        // first time need to see at least half of the interior corners or force
        if((this.calib.keyframes.isEmpty() && this.tracker.N_pts() >= this.allpts/2))
        {
            // try to estimate intrinsic params from single frame
            this.calib.calibrate(Arrays.asList(this.tracker.get_calib_pts()));

            if( /*! np.isnan(this.calib.K()).any() &&*/ this.calib.reperr() < this.min_reperr_init)
            {
                this.set_next_pose();  // update target pose
                this.tracker.set_intrinsics(this.calib);
                this.min_reperr_init = this.calib.reperr();
            }
        }

        this.pose_reached = force && this.tracker.N_pts() >= Cfg.minCorners; // original had > 4

        double pose_close_to_tgt = this.pose_close_to_tgt(); // save it to print it
        if(pose_close_to_tgt > Cfg.pose_close_to_tgt_min)
        {
            this.pose_reached = true;
        }
        // we need at least 57.5 points after 2 frames # rkt - the calc yields 27 with init nintr of 9, not 57.5
        // and 15 points per frame from then on
        int n_required = ((this.calib.nintr() + 2 * 6) * 5 + 3) / (2 * 2); // 27

        if(this.calib.keyframes.size() >= 2)
        {
            n_required = 6 / 2 * 5; // yup - that's a 15 rkt
        }

        this.still = this.tracker.mean_flow() < Cfg.mean_flow_max;
        // use all points instead to ensure we have a stable pose
        this.pose_reached &= this.tracker.N_pts() >= n_required;

        this.capture = this.pose_reached && (this.still || force);

        Main.LOGGER.log(Level.WARNING,
            "corners " + this.tracker.N_pts() +
            ", pose_close_to_tgt " + pose_close_to_tgt +
            ", still " + this.still +
            ", mean_flow " + this.tracker.mean_flow() +
            ", pose_reached " + this.pose_reached +
            ", force " + force);

        if( ! this.capture)
        {
            return false;            
        }

        this.calib.keyframes.add(this.tracker.get_calib_pts());

        // update calibration with all keyframe
        this.calibrate();

        // use the updated calibration results for tracking
        this.tracker.set_intrinsics(this.calib);

        this.converged = isAllTrue(this.pconverged);

        if(this.converged)
        {
            this.tgt_r.release();
            this.tgt_r = new Mat(); // clear the rotation
        }
        else
        {
            this.set_next_pose();
        }

        this._update_user_info();

        return true;
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     _update_user_info                                                      */
/*                                     _update_user_info                                                      */
/*                                     _update_user_info                                                      */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    private void _update_user_info()
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        this.user_info_text = "";

        if(this.calib.keyframes.size() < 2)
        {
                        this.user_info_text = "initialization";
        }
        else if( ! this.converged)
        {
            String action = "";
            int axis;
            if(this.tgt_param < 2)
            {
                action = "rotate";
                // do not consider r_z as it does not add any information
                double[] temp = {this.calib.pose_var()[0], this.calib.pose_var()[1]};
                axis = argmin(temp);
            }
            else
            {
                action = "translate";
                // do not consider t_z
                //FIXME above comment doesn't seem to match the code below
                double[] temp = {this.calib.pose_var()[3], this.calib.pose_var()[4], this.calib.pose_var()[5]};
                axis = argmin(temp) + 3; // find min of t_x, t_y, t_z and add 3 to that index to skip the rotation locations
            }
            String param = this.INTRINSICS[this.tgt_param];
            this.user_info_text = String.format("{%s} {%s} to minimize {%s}", action, this.POSE[axis], param);
            //translate 'ty' to minimize 'k3' this message comes with nearly 100% similarity but it still won't accept and move to next pose
        }
        else
        {
            this.user_info_text = "converged at MSE: {" + this.calib.reperr() + "}";
        }

        if (this.pose_reached && ! this.still)
        {
            this.user_info_text += "\nhold camera steady";
        }
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     draw                                                                   */
/*                                     draw                                                                   */
/*                                     draw                                                                   */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    // this adds the guidance board to the camera image to make the new user display
    void draw(Mat img, boolean mirror) // force users to specify mirror false instead of defaulting
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        // assumes both img and board are 3 color channels BGR
        if( ! this.tgt_r.empty())
        {
            // process one row at a time for more cpu efficiency than one element at a time
            byte[] imgBuffRow = new byte[img.cols()*img.channels()]; // temp buffers for efficient access to a row
            byte[] board_warpedBuffRow = new byte[this.board_warped.cols()*this.board_warped.channels()];
            for(int row = 0; row < img.rows(); row++)
            {
                img.get(row, 0, imgBuffRow); // get the row
                this.board_warped.get(row, 0,board_warpedBuffRow);
                for(int col = 0; col < imgBuffRow.length; col++) // process each element of the row
                {
                    // if there is a non-black pixel in the warped board then use it in img
                    if(board_warpedBuffRow[col] != 0)
                    {
                        imgBuffRow[col] = board_warpedBuffRow[col];
                    }
                }
                img.put(row, 0, imgBuffRow);
            }
        }
        if(this.tracker.pose_valid())
        {
            this.tracker.draw_axis(img); // draw axis on the detected board from the camera image
        }
        if(mirror)
        {
            Core.flip(img, img, 1);
        }
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     write                                                                  */
/*                                     write                                                                  */
/*                                     write                                                                  */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    void write()
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        Main.LOGGER.log(Level.SEVERE, "Writing the calibration data");
        Main.LOGGER.log(Level.SEVERE, "calibration_time " + LocalDateTime.now());
        Main.LOGGER.log(Level.SEVERE, "nr_of_frames " + this.calib.keyframes.size());
        Main.LOGGER.log(Level.SEVERE, "image_width " + this.calib.img_size().width);
        Main.LOGGER.log(Level.SEVERE, "image_height " + this.calib.img_size().height);
        Main.LOGGER.log(Level.SEVERE, "board_width " + this.tracker.board_sz().width);
        Main.LOGGER.log(Level.SEVERE, "board_height " + this.tracker.board_sz().height);
        Main.LOGGER.log(Level.SEVERE, "square_size " + this.square_len);
        Main.LOGGER.log(Level.SEVERE, "marker_size ", this.marker_len);
        Main.LOGGER.log(Level.SEVERE, formatFlags(calib.flags()));
        Main.LOGGER.log(Level.SEVERE, "fisheye_model " + 0);
        Main.LOGGER.log(Level.SEVERE, "camera_matrix\n" + this.calib.K().dump());
        Main.LOGGER.log(Level.SEVERE, "distortion_coefficients\n" + this.calib.cdist().dump());
        Main.LOGGER.log(Level.SEVERE, "avg_reprojection_error " + this.calib.reperr());
    }
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     formatFlags                                                            */
/*                                     formatFlags                                                            */
/*                                     formatFlags                                                            */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
    static String formatFlags(int flagsCalibration)
    {
        Main.LOGGER.log(Level.WARNING, "method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        HashMap<Integer, String> flags = new HashMap<>(3);
        flags.put(Calib3d.CALIB_FIX_PRINCIPAL_POINT, "+fix_principal_point");
        flags.put(Calib3d.CALIB_ZERO_TANGENT_DIST, "+zero_tangent_dist");
        flags.put(Calib3d.CALIB_USE_LU, "+use_lu");
        flags.put(Calib3d.CALIB_FIX_ASPECT_RATIO, " fix aspect ratio");
        flags.put(Calib3d.CALIB_FIX_PRINCIPAL_POINT, " fix principal point");
        flags.put(Calib3d.CALIB_ZERO_TANGENT_DIST, " zero tangent dist");
        flags.put(Calib3d.CALIB_FIX_K1, " fix k1");
        flags.put(Calib3d.CALIB_FIX_K2, " fix k2");
        flags.put(Calib3d.CALIB_FIX_K3, " fix k3");
       
        StringBuilder flags_str = new StringBuilder("flags: ");
        int unknownFlags = flagsCalibration; // initially assume all flags are unknown to the hashmap

        for(Map.Entry<Integer, String> flag : flags.entrySet())
        {
            if((flagsCalibration & flag.getKey()) == flag.getKey())
            {
                flags_str.append(flag.getValue());
                unknownFlags -= flag.getKey(); // this flag is known so un-mark unknown flags
            }                       
        }

        flags_str.append("\nflags ");
        flags_str.append(flagsCalibration);
        if(unknownFlags != 0)
        {
            flags_str.append("; unknown flag usage = ");
            flags_str.append(unknownFlags);          
        }
        return flags_str.toString();
    }
}
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
/*                                                                                                            */
/*                                     End UserGuidance class                                                 */
/*                                     End UserGuidance class                                                 */
/*                                     End UserGuidance class                                                 */
/*                                                                                                            */
/*----------------------------------------------------------------------------------------------------------- */
/*----------------------------------------------------------------------------------------------------------- */
