package com.android.example.cameraxbasic.fragments

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat

class Overlay constructor(context: Context?, attributeSet: AttributeSet?) :
    View(context, attributeSet) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context!!, android.R.color.black)
        strokeWidth = 10f
        textSize = 50f
    }

    private var overlay : Bitmap? = null
    private var bitmapMatrix : Matrix? = null
    // private var luma = 0.0

    private fun setBuffer(buffer: Bitmap) {
        overlay = buffer
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (overlay == null) {
            val rect = RectF(100f, 200f, 300f, 400f)
            canvas.drawRect(rect, paint)
        }
        else {
            Log.i("KK", "Drawing overlay with size ${overlay!!.width} ${overlay!!.height}")
            // val bitmap = BitmapFactory.decodeByteArray(overlay!!, 0, overlay!!.size)
            canvas.drawBitmap(
                overlay!!,
                bitmapMatrix!!,
                paint
            )
        }
        // Pass it a list of RectF (rectBounds)
        // rectBounds.forEach { canvas.drawRect(it, paint) }
    }

    fun processData(value: Bitmap, mat: Matrix, inv: Matrix) {
        overlay = value
        bitmapMatrix = mat
        invalidate()
    }
}