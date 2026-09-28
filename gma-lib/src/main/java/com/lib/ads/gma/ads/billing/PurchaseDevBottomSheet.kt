package com.lib.ads.gma.ads.billing

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.lib.ads.gma.gma.R
import com.android.billingclient.api.ProductDetails
import com.google.android.material.bottomsheet.BottomSheetDialog

internal class PurchaseDevBottomSheet(
    private val typeIap: Int,
    private val productId: String,
    private val productDetails: ProductDetails?,
    context: Context,
    private val onResult: ((PurchaseEvent) -> Unit)?
) : BottomSheetDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_billing_test)

        val txtTitle = findViewById<TextView>(R.id.txtTitle)
        val txtDescription = findViewById<TextView>(R.id.txtDescription)
        val txtId = findViewById<TextView>(R.id.txtId)
        val txtPrice = findViewById<TextView>(R.id.txtPrice)
        val txtContinuePurchase = findViewById<TextView>(R.id.txtContinuePurchase)

        try {
            val typeName = if (typeIap == 1) "inapp" else "subs"

            if (productDetails != null) {
                txtTitle?.text = productDetails.title
                txtDescription?.text = productDetails.description
                txtId?.text = productDetails.productId
                if (typeIap == 1) {
                    txtPrice?.text = productDetails.oneTimePurchaseOfferDetails?.formattedPrice
                } else {
                    val formattedPrice = productDetails.subscriptionOfferDetails
                        ?.getOrNull(0)
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.getOrNull(0)
                        ?.formattedPrice
                    txtPrice?.text = formattedPrice
                }
            } else {
                txtTitle?.text = "Test Product ($typeName)"
                txtDescription?.text = productId
                txtId?.text = productId
                txtPrice?.text = "Test - 0 VND"
            }

            txtContinuePurchase?.text = if (typeIap == 1) "Purchase" else "Subscribe"
            txtContinuePurchase?.setOnClickListener {
                val testOrderId = "test_order_${System.currentTimeMillis()}"
                val testJson = Companion.buildTestJson(productId, typeName, testOrderId)
                AppPurchase.getInstance().setPurchase(true)
                onResult?.invoke(PurchaseEvent.Success(testOrderId, productId, testJson))
                dismiss()
            }

            val touchOutsideView = window
                ?.decorView
                ?.findViewById<View>(com.google.android.material.R.id.touch_outside)
            touchOutsideView?.setOnClickListener {
                onResult?.invoke(PurchaseEvent.Cancelled)
                dismiss()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(-1, -2)
    }

    companion object {
        private fun buildTestJson(productId: String, productType: String, orderId: String): String =
            "{\"orderId\":\"$orderId\",\"productId\":\"$productId\",\"type\":\"$productType\"," +
                "\"purchaseToken\":\"test_token_${System.currentTimeMillis()}\"," +
                "\"purchaseState\":0,\"acknowledged\":false}"
    }
}
