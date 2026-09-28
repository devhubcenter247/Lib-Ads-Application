package com.lib.ads.gma.ads.billing

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import com.android.billingclient.api.ProductDetails
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lib.adlib.R

/**
 * Bottom sheet dialog for testing purchases in development mode.
 */
class PurchaseDevBottomSheet(
    private val typeIap: Int,
    private val productDetails: ProductDetails?,
    context: Context,
    private val purchaseListener: PurchaseListener?
) : BottomSheetDialog(context) {

    private var txtTitle: TextView? = null
    private var txtDescription: TextView? = null
    private var txtId: TextView? = null
    private var txtPrice: TextView? = null
    private var txtContinuePurchase: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_billing_test)

        txtTitle = findViewById(R.id.txtTitle)
        txtDescription = findViewById(R.id.txtDescription)
        txtId = findViewById(R.id.txtId)
        txtPrice = findViewById(R.id.txtPrice)
        txtContinuePurchase = findViewById(R.id.txtContinuePurchase)

        try {
            val productId: String
            if (productDetails != null) {
                txtTitle?.text = productDetails.title
                txtDescription?.text = productDetails.description
                txtId?.text = productDetails.productId
                productId = productDetails.productId

                if (typeIap == AppPurchase.TYPE_IAP.PURCHASE) {
                    txtPrice?.text = productDetails.oneTimePurchaseOfferDetails?.formattedPrice
                } else {
                    txtPrice?.text = productDetails.subscriptionOfferDetails
                        ?.getOrNull(0)
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.getOrNull(0)
                        ?.formattedPrice
                }
            } else {
                productId = AppPurchase.PRODUCT_ID_TEST
            }

            txtContinuePurchase?.setOnClickListener {
                purchaseListener?.let { listener ->
                    AppPurchase.getInstance().setPurchase(true)
                    listener.onProductPurchased(
                        productId,
                        "{\"productId\":\"android.test.purchased\",\"type\":\"inapp\",\"title\":\"Tiêu đề mẫu\",\"description\":\"Mô tả mẫu về sản phẩm: android.test.purchased.\",\"skuDetailsToken\":\"AEuhp4Izz50wTvd7YM9wWjPLp8hZY7jRPhBEcM9GAbTYSdUM_v2QX85e8UYklstgqaRC\",\"oneTimePurchaseOfferDetails\":{\"priceAmountMicros\":23207002450,\"priceCurrencyCode\":\"VND\",\"formattedPrice\":\"23.207 ₫\"}}', parsedJson={\"productId\":\"android.test.purchased\",\"type\":\"inapp\",\"title\":\"Tiêu đề mẫu\",\"description\":\"Mô tả mẫu về sản phẩm: android.test.purchased.\",\"skuDetailsToken\":\"AEuhp4Izz50wTvd7YM9wWjPLp8hZY7jRPhBEcM9GAbTYSdUM_v2QX85e8UYklstgqaRC\",\"oneTimePurchaseOfferDetails\":{\"priceAmountMicros\":23207002450,\"priceCurrencyCode\":\"VND\",\"formattedPrice\":\"23.207 ₫\"}}, productId='android.test.purchased', productType='inapp', title='Tiêu đề mẫu', productDetailsToken='AEuhp4Izz50wTvd7YM9wWjPLp8hZY7jRPhBEcM9GAbTYSdUM_v2QX85e8UYklstgqaRC', subscriptionOfferDetails=null}"
                    )
                }
                dismiss()
            }

            window?.decorView?.findViewById<android.view.View>(com.google.android.material.R.id.touch_outside)?.setOnClickListener {
                purchaseListener?.onUserCancelBilling()
                dismiss()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
