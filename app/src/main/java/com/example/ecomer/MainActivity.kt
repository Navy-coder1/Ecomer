package com.example.ecomer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.Locale

private data class Product(
    val id: String = "",
    val name: String = "",
    val category: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val imagePath: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EcomerApp() } }
    }
}

@Composable
private fun EcomerApp() {
    val auth = remember { FirebaseAuth.getInstance() }
    var signedIn by remember { mutableStateOf(auth.currentUser != null) }
    var isAdmin by remember { mutableStateOf(false) }
    var checkingRole by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun logout() {
        auth.signOut()
        signedIn = false
        isAdmin = false
        checkingRole = false

        Toast.makeText(
            context,
            "Đã đăng xuất",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun checkRole() {
        val user = auth.currentUser
        if (user == null) { signedIn = false; isAdmin = false; return }
        checkingRole = true
        FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
            .addOnSuccessListener { doc ->
                Log.d("CheckRole", "UID: ${user.uid}")
                Log.d("CheckRole", "Exists: ${doc.exists()}")
                Log.d("CheckRole", "Role: ${doc.getString("role")}")

                isAdmin = doc.getString("role") == "admin"
                signedIn = true
                checkingRole = false
            }
            .addOnFailureListener { e ->
                checkingRole = false
                isAdmin = false
                Toast.makeText(context, "Không đọc được quyền: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    LaunchedEffect(Unit) { if (auth.currentUser != null) checkRole() }

    if (!signedIn) {
        LoginScreen(onLogin = { email, password ->
            auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnSuccessListener { checkRole() }
                .addOnFailureListener { Toast.makeText(context, "Đăng nhập thất bại: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
        })
    } else if (checkingRole) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (!isAdmin) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tài khoản này không có quyền admin.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { auth.signOut(); signedIn = false; isAdmin = false }) { Text("Đăng xuất") }
        }
    } else {
        ProductAdminScreen(onLogout = {logout()})
    }
}

@Composable
private fun LoginScreen(onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ECOMER - ADMIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Mật khẩu") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (email.isBlank() || password.isBlank()) return@Button else onLogin(email, password) }, modifier = Modifier.fillMaxWidth()) {
            Text("ĐĂNG NHẬP")
        }
        Spacer(Modifier.height(8.dp))
        Text("Tạo tài khoản trong Firebase Authentication; admin cần được cấp role=admin trong Firestore.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProductAdminScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val storage = remember { FirebaseStorage.getInstance() }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var selectedImageName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var oldImagePath by remember { mutableStateOf("") }
    var oldImageUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val registration = db.collection("products").addSnapshotListener { snapshot, error ->
            if (error != null) { errorText = error.localizedMessage; return@addSnapshotListener }
            products = snapshot?.documents?.map { doc ->
                Product(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    category = doc.getString("category") ?: "",
                    price = doc.getDouble("price") ?: (doc.getLong("price")?.toDouble() ?: 0.0),
                    imageUrl = doc.getString("imageUrl") ?: "",
                    imagePath = doc.getString("imagePath") ?: ""
                )
            } ?: emptyList()
        }
        onDispose { registration.remove() }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
        selectedImageName = uri?.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    fun clearForm() {
        name = ""; category = ""; priceText = ""; selectedImage = null
        selectedImageName = ""; editing = null; oldImagePath = ""; oldImageUrl = ""
    }

    fun saveProduct() {
        val price = priceText.trim().replace(',', '.').toDoubleOrNull()
        if (name.isBlank() || category.isBlank() || price == null || price < 0) {
            errorText = "Nhập đủ tên, loại và giá hợp lệ."; return
        }
//        if (editing == null && selectedImage == null) {
//            errorText = "Vui lòng chọn ảnh sản phẩm."; return
//        }
        busy = true; errorText = null
        val productId = editing ?: db.collection("products").document().id
        val imageRef = if (selectedImage != null) storage.reference.child("product_images/$productId-${System.currentTimeMillis()}.jpg") else null

        fun writeDocument(imageUrl: String, imagePath: String) {
            val data = hashMapOf<String, Any>(
                "name" to name.trim(),
                "category" to category.trim(),
                "price" to price,
                "imageUrl" to imageUrl,
                "imagePath" to imagePath,
                "createdAt" to Timestamp.now()
            )
            db.collection("products").document(productId).set(data)
                .addOnSuccessListener {
                    if (editing != null && oldImagePath.isNotBlank() && oldImagePath != imagePath) {
                        storage.reference.child(oldImagePath).delete()
                    }
                    busy = false; clearForm()
                    Toast.makeText(context, "Đã lưu sản phẩm", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { busy = false; errorText = "Lưu Firestore lỗi: ${it.localizedMessage}" }
        }

        if (selectedImage != null && imageRef != null) {
            imageRef.putFile(selectedImage!!)
                .continueWithTask { task ->
                    if (!task.isSuccessful) throw (task.exception ?: Exception("Upload ảnh thất bại"))
                    imageRef.downloadUrl
                }
                .addOnSuccessListener { uri -> writeDocument(uri.toString(), imageRef.path) }
                .addOnFailureListener { busy = false; errorText = "Tải ảnh lỗi: ${it.localizedMessage}" }
        } else {
            writeDocument(oldImageUrl, oldImagePath)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Dữ liệu sản phẩm", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    Log.d("Logout", "Đã nhấn nút đăng xuất")
                    onLogout()
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = "Đăng xuất"
                )
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Tên sản phẩm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(category, { category = it }, label = { Text("Loại sản phẩm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(priceText, { priceText = it }, label = { Text("Giá sản phẩm (VNĐ)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (selectedImage != null) "Đã chọn ảnh: $selectedImageName" else if (oldImageUrl.isNotBlank()) "Chọn ảnh mới (đang có ảnh)" else "Chọn ảnh sản phẩm")
        }
        if (selectedImage != null) {
            Image(painter = rememberAsyncImagePainter(selectedImage), contentDescription = "Ảnh được chọn", modifier = Modifier.size(84.dp).padding(4.dp), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { saveProduct() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (editing == null) "THÊM SẢN PHẨM" else "LƯU CHỈNH SỬA")
        }
        if (editing != null) TextButton(onClick = { clearForm(); errorText = null }) { Text("Hủy sửa") }
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        Text("Danh sách sản phẩm (${products.size})", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (products.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("Chưa có sản phẩm") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(products, key = { it.id }) { product ->
                    Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (product.imageUrl.isNotBlank()) {
                                Image(painter = rememberAsyncImagePainter(product.imageUrl), contentDescription = product.name,
                                    modifier = Modifier.size(76.dp), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Tên sp: ${product.name}", style = MaterialTheme.typography.bodyMedium)
                                Text("Giá sp: ${String.format(Locale.getDefault(), "%,.0f", product.price)} đ", style = MaterialTheme.typography.bodyMedium)
                                Text("Loại sp: ${product.category}", style = MaterialTheme.typography.bodyMedium)
                            }
                            Column {
                                IconButton(onClick = {
                                    name = product.name; category = product.category
                                    priceText = if (product.price % 1.0 == 0.0) product.price.toLong().toString() else product.price.toString()
                                    editing = product.id; oldImagePath = product.imagePath; oldImageUrl = product.imageUrl
                                    selectedImage = null; selectedImageName = ""; errorText = null
                                }) { Icon(Icons.Default.Edit, contentDescription = "Sửa sản phẩm", tint = MaterialTheme.colorScheme.primary) }
                                IconButton(onClick = {
                                    db.collection("products").document(product.id).delete()
                                        .addOnSuccessListener {
                                            if (product.imagePath.isNotBlank()) storage.reference.child(product.imagePath).delete()
                                            Toast.makeText(context, "Đã xóa sản phẩm", Toast.LENGTH_SHORT).show()
                                        }
                                        .addOnFailureListener { errorText = "Xóa lỗi: ${it.localizedMessage}" }
                                }) { Icon(Icons.Default.Delete, contentDescription = "Xóa sản phẩm", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}
