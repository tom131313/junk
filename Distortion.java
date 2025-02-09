// This project and file are derived in part from the "Pose Calib" project by
// @author Pavel Rojtberg
// It is subject to his license terms in the PoseCalibLICENSE file.

package Guidance;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     Distortion class                                            */
/*                                     Distortion class                                            */
/*                                     Distortion class                                            */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
class Distortion
{
    private static Logger LOGGER;
    static {
      LOGGER = Logger.getLogger("");
      LOGGER.finer("Loading");
    } 

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     get_bounds                                                  */
/*                                     get_bounds                                                  */
/*                                     get_bounds                                                  */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    private static Rect get_bounds(Mat thresh, Mat mask)
    {
        // seems like a better strategy would be to see what contour actually contributes the most and not just check the largest ones
        // and use true area of contour and not just the number of points in the contour
        LOGGER.finer("method entered  . . . . . . . . . . . . . . . . . . . . . . . .");
        LOGGER.finest("thresh " + thresh);        
        LOGGER.finest("mask " + mask);

        List<MatOfPoint> contours = new ArrayList<>(20); // arbitrary initial size - what is a better guess?
        Mat hierarchy = new Mat();
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
       
        LOGGER.finest(contours.size() + " contours");
        // look for the largest object that is not masked
        // This is essentially a Sort and Filter. It's not very efficient but that makes it easier
        // by not having to reorder the contours. The list is expected to be very short so it's not
        // a problem.

        // outer loop to allow the contours list to be trimmed by the filter
        // since Java disallows changing a list within an iterator
        while(contours.size() > 0)
        {
            // look for contour with largest area
            double areaContourMax = -1.; // indicate no max at the start
            int mx = -1; // indicate no max at the start
            for (int i = 0; i < contours.size(); i++)
            {
                // long areaContour = contours.get(i).total(); // original area was just the count of the points in the contour
                double areaContour = Imgproc.contourArea(contours.get(i)); // this might be better area but watch for area = 0.0 if only 2 points in contour

                if ( areaContour >= areaContourMax)
                {
                    // new max and its location
                    areaContourMax = areaContour;
                    mx = i;
                }
                LOGGER.finest("Contour " + (mx+1) + " of " + contours.size() + ", area max so far " + areaContourMax
                      + ", contour size " + contours.get(mx).size(mx) + "\n" + contours.get(mx).dump());
            }
            // Now have contour with largest area so check that area not already covered,
            // that is, it's not masked.
            // If already mostly masked then this contour doesn't contribute
            // enough so delete it. Else it's good and return it.
            Rect aabb = Imgproc.boundingRect(contours.get(mx));
            int x = aabb.x;
            int y = aabb.y;
            int w = aabb.width;
            int h = aabb.height;
            LOGGER.finest("processing Rect aabb " + aabb);

            if ( ! mask.empty() // amount of mask already filled where this contour would fill
                && (double)Core.countNonZero(mask.submat(y, y+h, x, x+w)) / (double)(w*h) > Cfg.MAX_OVERLAP)
            {
                contours.remove(mx); // largest contour wouldn't contribute enough in the right places so skip it
                continue;
            }
            LOGGER.finest("returning aabb " + aabb); // best contributing contour for the pose
            return aabb; // best contour in list so return it
        }

        LOGGER.finest("returning null aabb"); // pose doesn't contribute enough

        return null; // no contours met the criteria
    }
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     make_distort_map                                            */
/*                                     make_distort_map                                            */
/*                                     make_distort_map                                            */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    /**
     * creates a map for distorting an image as a opposed to the default behaviour of undistorting
     * 
     * @param K
     * @param sz width, height
     * @param dist
     * @param Knew
     * @return
     */
    static Mat make_distort_map(Mat K, Size sz, Mat dist, Mat Knew)
    {
        LOGGER.finer("method entered  . . . . . . . . . . . . . . . . . . . . . . . .");
        LOGGER.finest("camera matrix K " + K + "\n" + K.dump());
        LOGGER.finest("sz " + sz);
        LOGGER.finest("distortion coefficients dist " + dist.dump() + dist);
        LOGGER.finest("Knew " + Knew.dump()); // null pointer (or empty?) Knew

        // pts = np.array(np.meshgrid(range(sz[0]), range(sz[1]))).T.reshape(-1, 1, 2)
                // inclusive 0, to not included final, step; fills one column down the rows then the next column and down the rows
                // then transposes and reshapes to long and skinny
        // build grid in the correct final order (and shape) so the pair of transposes aren't needed
        int w = (int)sz.width; // columns
        int h = (int)sz.height; // rows
        int c = 2; // MatOfPoint2f channels, x and y axes
        // make smaller 2-D Mat of x,y points from full size image Mat
        // the values in the smaller Mat are the original x, y coordinates from the larger Mat

        MatOfPoint2f pts = new MatOfPoint2f();
        pts.alloc(h*w);
        // make 2d meshgrid but flattened to 1 dimension - lots of rows and 1 column each element is essentially Point2f
        // created mesh grid with flipped rows/cols so the transpose isn't needed like the np.meshgrid
        float[] ptsMeshGrid = new float[w*c]; // intermediate 1d version of 2d points for a column; X, Y pair
        int indexRectangularRowChannel; // one row of the rectangular grid
        int indexLinearRow = 0; // rows in the long skinny Mat
        for (int y = 0; y < h; y++) // traverse all rows of the rectangle grid
        {
            indexRectangularRowChannel = 0;
            for (int x = 0; x < w; x++) // traverse all columns of the rectangle grid
            {
                ptsMeshGrid[indexRectangularRowChannel++] = x;
                ptsMeshGrid[indexRectangularRowChannel++] = y;
            }
            pts.put(indexLinearRow, 0, ptsMeshGrid);
            indexLinearRow += indexRectangularRowChannel/c; // next starting row of the very long skinny Mat for the rectangular grid row
        }
        // grid is built

        MatOfPoint2f dpts = new MatOfPoint2f();

        Calib3d.undistortPointsIter(pts, dpts, K, dist, new Mat(), Knew, Cfg.undistortPointsIterCriteria);
        
        Mat dpts2D = dpts.reshape(2, h);

        LOGGER.finest("pts " + pts + "\n" + ArrayUtils.brief(pts));
        LOGGER.finest("dpts " + dpts + "\n" + ArrayUtils.brief(dpts));
        LOGGER.finest("returning dpts2D " + dpts2D + ArrayUtils.brief(dpts2D));
        LOGGER.finest("maybe returning Knew\n" + Knew.dump());

        pts.release();
        dpts.release();

        return dpts2D;
    }
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     sparse_undistort_map                                        */
/*                                     sparse_undistort_map                                        */
/*                                     sparse_undistort_map                                        */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    //     same output as initUndistortRectifyMap, but sparse
    //     @param sz: width, height
    //     @return: distorted points, original points
    static List<Mat> sparse_undistort_map(Mat K, Size sz, Mat dist, Mat Knew, int step)
    {
        LOGGER.finer("method entered  . . . . . . . . . . . . . . . . . . . . . . . .");

        // best I can tell step is always 20 (subsample) and never 1 so this should not be executed
        if (step == 1)
        {
            throw new IllegalArgumentException("step = 1 full image sampling not converted and tested");
        }
        // make smaller 2-D Mat of x,y points from full size image Mat
        // the values in the smaller Mat are the original x, y coordinates from the larger Mat

        // inclusive 0 to step
        int w = (int)sz.width/step; // columns
        int h = (int)sz.height/step; // rows
        int c = 2; // x and y axes
        // OpenCV methods require linear 1-column Mats flattened from the 2d mesh grid
        int indexLinearRow;
        MatOfPoint2f pts = new MatOfPoint2f();
        pts.alloc(h*w);
        float[] ptsMeshGrid = new float[w*h*c]; // intermediate 2d points

        indexLinearRow = 0;
        for (float y = 0.f; y < h; y += step)
        {
            for (float x = 0.f; x < w; x += step)
            {
                ptsMeshGrid[indexLinearRow++] = x;
                ptsMeshGrid[indexLinearRow++] = y;
            }
        }
        pts.put(0, 0, ptsMeshGrid);

        MatOfPoint2f ptsUndistorted = new MatOfPoint2f(); // intermediate 2d points
        MatOfPoint3f pts3d = new MatOfPoint3f(); // 3d points; Z = 0 added to the 2d to make 3d
        Mat zero = Mat.zeros(3, 1, CvType.CV_32FC1);
        
        Calib3d.undistortPointsIter(pts, ptsUndistorted, Knew, new Mat(), new Mat(), new Mat(), Cfg.undistortPointsIterCriteria); // undistort the 2d points

        Calib3d.convertPointsToHomogeneous(ptsUndistorted, pts3d); // now convert 2d to 3d homogeneous
        // n by 2 or 3 dimensions in or 2 or 3 dimensions by n in; always nx1x 3 or 4 channels out
        // a dimension is either a Mat row or column or 1 row or column and 2 or 3 channels
        // 1xnx2, 1xnx3, nx1x2, nx1x3, nx2x1, nx3x1 in; always nx1x3 or nx1x4 out

        MatOfDouble distOfDouble = new MatOfDouble(dist); // convert as required for the projectPoints()

        MatOfPoint2f dpts = new MatOfPoint2f();

        Calib3d.projectPoints(pts3d, zero, zero, K, distOfDouble, dpts); // project points in 3d back to a 2d screen

        int[] shape = {w, h};
        Mat dpts2D = dpts.reshape(c, shape);
        Mat pts2D = pts.reshape(c, shape);
        // reformat sparse flat MatOfPoint2f to 2-D 2-channel rectangular subsample map
        List<Mat> maps = new ArrayList<Mat>(2);

        pts.release();
        ptsUndistorted.release();
        pts3d.release();
        zero.release();
        distOfDouble.release();
        dpts.release();

        maps.add(dpts2D);
        maps.add(pts2D);

        return maps;
    }

/**
 *    get_diff_heatmap  NOT USED -- NOT CONVERTED
 *    get_diff_heatmap  NOT USED -- NOT CONVERTED
 *    get_diff_heatmap  NOT USED -- NOT CONVERTED
*/

/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     loc_from_dist                                               */
/*                                     loc_from_dist                                               */
/*                                     loc_from_dist                                               */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    /**
     *     compute location based on distortion strength
     * @param pts: sampling locations
     * @param dpts: distorted points
     * @param mask: mask for ignoring locations
     * @param lower: find location with minimal distortion instead
     * @param thres: distortion strength to use as threshold [%]
     * @return
     */
    static Rect loc_from_dist(Mat pts, Mat dpts, Mat mask, boolean lower, double thres) // force specifying all parameters
    {
        LOGGER.finer("method entered  . . . . . . . . . . . . . . . . . . . . . . . .");
        LOGGER.finest("pts " + pts);
        LOGGER.finest("dpts " + dpts);
        LOGGER.finest("mask " + mask);
        LOGGER.finest("lower " + lower);
        LOGGER.finest("thres " + thres);
        Mat diffpts = new Mat();
        Core.subtract(pts, dpts, diffpts);
        LOGGER.finest("diffpts " + diffpts);

        Mat normMat = new Mat(pts.rows(), pts.cols(), CvType.CV_32FC1);
        LOGGER.finest("normMat empty " + normMat);

        for (int row = 0; row < pts.rows(); row++)
        for (int col = 0; col < pts.cols(); col++)
        {
            float[] point = new float[2]; // get the 2 channels of data x in 0 and y in 1
            diffpts.get(row, col, point);
            float norm = (float)Math.sqrt(Math.pow(point[0], 2) + Math.pow(point[1], 2)); // L2 norm (Frobenious)
            normMat.put(row, col, norm);
        }
        LOGGER.finest("normMat filled " + normMat);

        normMat = normMat.reshape(0,mask.rows())/*.t()*/;
        LOGGER.finest("normMat reshaped " + normMat);

        Mat diff = new Mat();
        Core.normalize(normMat, diff, 0, 255, Core.NORM_MINMAX, CvType.CV_8U);
        LOGGER.finest("diff " + diff.dump());
        LOGGER.finest("normMat normalized=diff " + diff);

        Rect bounds = null;

        Mat thres_img = new Mat();
        while ((bounds == null) && (thres >= 0.) && (thres <= 1.) )
        {
            if (lower)
            {
                thres += 0.05;
                Imgproc.threshold(diff, thres_img, thres * 255., 255., Imgproc.THRESH_BINARY_INV);
            }
            else
            {
                thres -= 0.05;
                Imgproc.threshold(diff, thres_img, thres * 255., 255., Imgproc.THRESH_BINARY);
            }
            LOGGER.finest("thres_img " + thres_img /*+ "\n" + brief(thres_img.dump())*/);

            bounds = get_bounds(thres_img, mask);

            if (bounds == null)
            {
                continue;
            }

            if (bounds.width*bounds.height == 0) // ensure area is not 0
            {
                bounds = null;                
            }
        }

        normMat.release();
        diff.release();
        diffpts.release();

        LOGGER.finest("bounds " + (bounds == null ? "is null" : bounds));

        return bounds;
    }
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
/*                                                                                                 */
/*                                     Distortion constructor                                      */
/*                                     Distortion constructor                                      */
/*                                     Distortion constructor                                      */
/*                                                                                                 */
/*-------------------------------------------------------------------------------------------------*/
/*-------------------------------------------------------------------------------------------------*/
    private Distortion()
    {
        throw new UnsupportedOperationException("This is a utility class");
    }
}
