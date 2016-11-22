package tsframe

import tsframe.DTW._
import glint.Client
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext

/*
import org.apache.spark.sql._
import org.apache.spark.sql.DataFrame
import org.apache.spark.Accumulable
// import does not work, use type aliasing as a workaround
type Row = org.apache.spark.sql.Row
type DataFrame = org.apache.spark.sql.DataFrame
type Accumulable[A, B] = org.apache.spark.Accumulable[A, B]
*/

class TSFrame(data: Array[MDVector]) extends java.io.Serializable {

    def normalize(x: MDVector, mean: MDVector, std: MDVector): MDVector = (x - mean) / std
    def dist(a: MDVector, b: MDVector): Double = (a - b).magnitudeSquared

    def sequentialDTW(it: Iterator[MDVector], q: OrderedQuery, window_size: Int): (Long, Double) = {
        var location: Long = 0L

        import scala.language.implicitConversions
        implicit def LongtoInt(l: Long): Int = l.intValue

        val query_norm: Array[MDVector] = q.query_norm
        val (upper: Array[Array[Double]], lower: Array[Array[Double]]) = MDEnvelope(query_norm, window_size)
        var local_bsf: Double = scala.Double.MaxValue
        // a circular array used to store the current candidate
        // double the size for avoiding using modulo % operator
        val m: Int = query_norm.length
        val buffer: Array[MDVector] = Array.ofDim[MDVector](2 * m)
        val dimension: Int = query_norm(0).dimension
        val ex: MDVector = new MDVector(dimension)
        val ex2: MDVector = new MDVector(dimension)
        
        
        var i: Long = 0
        while(it.hasNext && i < m-1){
            //val current_row: MDVector = toMDVector(it.next)
            val current_row: MDVector = it.next
            buffer(i) = current_row
            buffer(i + m) = current_row
            ex += current_row
            ex2 += (current_row * current_row)
            i += 1
        }
        while(it.hasNext){
            //val current_row: MDVector = toMDVector(it.next)
            val current_row: MDVector = it.next
            buffer(i % m) = current_row
            buffer(i % m + m) = current_row
            ex += current_row
            ex2 += (current_row * current_row)
            i += 1

            @transient val client = Client(ConfigFactory.parseFile(new java.io.File("/src/main/resources/glint.conf")))		//add
            val bsfVector = client.vector[Double](1)										//add
            @transient implicit val ec = ExecutionContext.Implicits.global							//add

            var current_distance: Double = scala.Double.MaxValue
            val mean: MDVector = ex / m
            val std: MDVector = (ex2 / m - mean * mean).sqrt
            val start_index: Int = i % m;

            bsfVector.pull(Array(0L)).onSuccess{ case values => local_bsf = values(0)						//add

            val lb_kim: Double = LBKim(dist)(buffer, start_index, query_norm, mean, std, local_bsf)
            if(lb_kim < local_bsf){
                val (cb1: Array[Double], lb_keogh: Double) = LBKoegh(buffer, start_index, upper, lower, mean, std, local_bsf)
                if(lb_keogh < local_bsf){
                    val (c_upper: Array[Array[Double]], c_lower: Array[Array[Double]]) = MDEnvelope(buffer, window_size)
                    val (cb2: Array[Double], lb_keogh2: Double) = LBKoegh2(c_upper, c_lower, query_norm, mean, std, local_bsf)
                    if(lb_keogh2 < local_bsf){
                        // Choose better lower bound between lb_keogh and lb_keogh2 to be used in early abandoning DTW
                        // Note that cb1 or cb2 will be cumulative summed here.
                        // The size of cb is m+1 (while the size of cb1 and cb2 is m), but it does not matter
                        // since DTWCalculator will not use the last element (it only uses the first m elements)
                        val cb: Array[Double] = (if(lb_keogh < lb_keogh2) cb2 else cb1)
                                                .scanRight(0.0){ (x, acc) => acc + x }
                        val c_norm: Array[MDVector] = Array.ofDim[MDVector](m)
                        for(i <- 0 until m){
                            c_norm(i) = (buffer(i + start_index) - mean) / std
                        }
                        current_distance = DTWCalculator(dist)(c_norm, query_norm, cb, window_size, local_bsf)
                    }
                }
            }
            val first_row = buffer(start_index)
            ex -= first_row
            ex -= (first_row * first_row)
            if(current_distance < local_bsf){
  
                bsfVector.push(Array(0L), Array(current_distance))									//add

                location = i - m
                local_bsf = current_distance
                // update global bsf
            }
            // retrieve global bsf
        }
        //distAccum += local_bsf
        (location, local_bsf)
    }
}
