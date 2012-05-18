#include <windows.h>
#include <io.h>

#include <iostream>
#include <fstream>
using namespace std;

int main(void)
{
	HANDLE ph=CreateNamedPipe(
	 "\\\\.\\pipe\\kawarilog",
	 PIPE_ACCESS_DUPLEX,
	 0,
	 1,
	 1024,
	 1024,
	 INFINITE,
	 NULL);

	if(ph==INVALID_HANDLE_VALUE) {
		cerr << "error" << endl;
		return(1);
	}

	cout << "waiting..." << endl;
	if(ConnectNamedPipe(ph,NULL)) {
		ifstream fs(_open_osfhandle((long)ph,_O_RDONLY | _O_TEXT));

		string buff;
		while(!fs.eof()) {
			getline(fs,buff);
			cout << buff << endl;
		}
	}

	return(0);
}

